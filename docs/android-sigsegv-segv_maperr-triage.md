## Android Native 崩溃排查：Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)

你给的片段：

- `Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x3c ... tid ... (pool-5-thread-1) ...`

### 1) 这类日志通常代表什么

- **SIGSEGV**：进程访问了非法内存地址（段错误）。
- **SEGV_MAPERR**：地址**没有被映射**（常见是空指针/野指针/越界后得到的无效指针）。
- **fault addr 0x3c（或接近 0）**：非常典型的“**接近空地址的偏移访问**”，例如 `ptr == null` 但代码读 `ptr->field`，field 偏移正好是 `0x3c`。
- **发生在线程池线程 `pool-*-thread-*`**：多见于 JNI/NDK、本地库、推理引擎、音视频库等在后台线程执行时触发。

> 结论：这基本不是 Java/Kotlin 层抛异常能直接定位的问题，优先按 “tombstone + 符号化” 走。

---

### 2) 你需要补齐的关键日志（最少集）

请尽量提供以下信息（缺一会显著降低定位速度）：

- **完整崩溃堆栈**（logcat 中 crash 段落从 “*** *** ***” 开始到结尾）
- **Build fingerprint** / Android 版本 / 设备型号 / CPU ABI（arm64-v8a / armeabi-v7a 等）
- **进程名与包名**（你片段里像 `vw.modelser...`，需要完整值）
- **崩溃当时加载的本地库列表**（tombstone 里 `backtrace`、`mapped files`、`/proc/pid/maps` 摘要）
- 如果是自研 so：**对应版本的符号文件**（未 strip 的 `.so` / `.so.dbg` / 带 symbols 的构建产物）

---

### 3) 抓取 tombstone（推荐）

在复现崩溃后，执行：

```bash
# 1) 直接拉取 tombstones（设备需开启 adb 调试）
adb root || true
adb wait-for-device
adb shell ls -lt /data/tombstones | head
adb pull /data/tombstones ./tombstones

# 2) 同时拉取 anr / crash 相关（按需）
adb pull /data/anr ./anr || true
```

如果设备不允许 `adb root`，也可以通过：

- `adb bugreport`（很多机型会包含 tombstone/trace）
- 厂商定制路径（例如 `/data/vendor/tombstones/`、`/data/vendor/` 下）

---

### 4) 符号化（把地址变成函数名/源码行）

#### 4.1 用 Android NDK 的 `ndk-stack`（最常用）

准备：

- NDK（本地或 CI 环境）
- 你的 `symbols/` 目录：包含对应 ABI 与对应版本的未 strip 符号（或 `.so`+`.sym`）

执行：

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk

# tombstone_XX.txt 来自 /data/tombstones
$ANDROID_NDK_HOME/ndk-stack -sym /path/to/symbols -dump tombstone_XX.txt > tombstone_XX.symbolicated.txt
```

#### 4.2 用 `addr2line` / `llvm-symbolizer` 精确定位

如果 backtrace 里有类似：

- `pc 0000000000123456  /data/app/.../lib/arm64/libxxx.so (func+offset)`

可以把 `pc` 转成文件行号（需要未 strip 的 so）：

```bash
llvm-addr2line -C -f -e /path/to/libxxx.so 0x123456
```

> 注意：不同 Android 版本/架构下地址是否需要减去基址，需要结合 tombstone 的映射区间判断。

---

### 5) 最常见根因清单（按命中率排序）

- **空指针/未初始化对象**：fault addr 很小（如 `0x3c`）强烈暗示这一类。
- **Use-after-free**：对象已释放，后台线程仍在用（线程池更常见）。
- **数组越界/写坏内存**：可能在后续任意点崩溃，堆栈不一定直接指向“元凶”。
- **ABI/so 版本不匹配**：例如加载了不兼容版本的推理引擎/依赖库，或 32/64 位混用。
- **并发问题**：多线程读写同一 native 结构体未加锁，导致偶发崩溃。
- **JNI 层引用生命周期问题**：`jobject` 未转全局引用跨线程使用、或 `GetStringUTFChars` 未正确释放等。
- **模型/输入数据不合法**：native 代码未做边界校验（例如 width/height/stride 不一致、buffer 长度不足）。

---

### 6) 快速缩小范围的建议（你可以立刻做）

- **打开更严格的 native 检测**（debug/测试环境）：
  - ASan / HWASan（如果能切到专用构建）
  - `-fsanitize=address`（ASan）或 Android HWASan 系统镜像/设备
- **记录关键输入参数**：
  - 推理：tensor shape、dtype、buffer size、batch、stride
  - 图像：w/h/format/rowStride/pixelStride、byte[] 长度
  - 音视频：sample rate、channels、frame size、pts/dts
- **对可疑指针访问加防御**（native 入口处）：
  - 任何指针解引用前判空
  - 对长度、索引、stride 做上界校验
- **确认线程/生命周期**：
  - Java 层对象被销毁/Stop 后，native 后台线程是否仍在跑
  - 模型/会话对象是否在多线程共享（是否线程安全）

---

### 7) 如果你只能提供一条额外信息

请提供 tombstone 里这两段（最关键）：

- `backtrace:`（包含每帧的 `pc` 与 so 名称）
- `ABI:`（以及 `Build fingerprint:`）

拿到这两段基本就能判断：崩溃发生在哪个 so、哪个函数附近，以及是否与 ABI/版本相关。

---

### 8) 针对 FFmpeg 抽帧崩溃（`avformat_find_stream_info`）的专项排查

你补充的崩溃很典型：

- `signal 11 ... fault addr 0x3c` / `Cause: null pointer dereference`
- `#01 ... libavformat.so (avformat_find_stream_info+508)`
- 由 JNI 入口 `Java_com_modelbest_ffmpeg_FFmpeg_extractFramesFromUriWithTimeRange` 触发

这类崩溃的高概率根因不是“文件坏了”这么简单，而是**FFmpeg API 使用/错误处理不完整**或**自定义 IO 接入不规范**导致内部解引用空指针。

#### 8.1 最优先检查：所有返回值都必须判断，失败就直接返回（不要继续走）

最常见误用链路是：

1. `avformat_alloc_context()` 返回空（内存不足等）但仍继续
2. `avio_alloc_context()` / 自定义 read/seek 回调初始化失败，导致 `fmt->pb == NULL`
3. `avformat_open_input()` 返回负值（打不开 URI/权限/IO 失败/回调不符合约定）但仍调用 `avformat_find_stream_info()`

只要满足任意一种，“在 `find_stream_info` 里崩溃、fault addr 很小”就非常常见。

#### 8.2 如果你是“从 Uri 读”（ContentResolver/自定义 read 回调），重点核对这些约定

FFmpeg 的 read/seek 回调若实现不符合约定，会触发不可预期行为（包括崩溃）：

- **read 回调**：
  - 成功读取：返回 `>0`（实际字节数）
  - 文件结束：返回 `AVERROR_EOF`
  - 出错：返回负的 `AVERROR(errno)`（不要返回 0 表示“暂时没数据”，这在很多路径会被当成 EOF 或触发异常分支）
- **seek 回调**（如果实现了）：
  - 必须正确处理 `whence == AVSEEK_SIZE`（返回媒体大小，无法提供就返回负值）
  - 返回值是新的绝对位置（字节偏移），失败返回负值
- **线程安全**：
  - `pool-*-thread-*` 里跑抽帧时，底层文件句柄/FD/Java InputStream 不要被其他线程关闭
  - `jobject`（如 `InputStream`/`ParcelFileDescriptor`）跨线程使用必须转 **GlobalRef**，并保证生命周期覆盖 native 调用

#### 8.3 版本/库一致性：确保 `libavformat/libavcodec/libavutil/...` 是同一套构建产物

你 log 里显示 `libavformat.so` + 自己的 `libffmpeg_android.so`。

- 如果 `libffmpeg_android.so` 里 **静态链接** 了部分 FFmpeg，同时 APK 又 **动态加载** 另一套 `libavformat.so`，可能出现符号/结构体布局不一致，导致空指针/越界。
- 建议确认：最终运行时只使用**一套** FFmpeg（全静态或全动态），并且所有 FFmpeg so 的版本/配置一致。

#### 8.4 快速自证：把 FFmpeg 日志打出来，定位卡在 open 还是 probe

在 JNI 入口里尽早调用：

- `av_log_set_level(AV_LOG_DEBUG)`（必要时 `AV_LOG_TRACE`）
- `av_log_set_callback` 把日志转到 Android logcat

然后关注 `avformat_open_input` 与 `avformat_find_stream_info` 前后的返回值与关键指针（`fmt != NULL`、`fmt->pb != NULL`）。

#### 8.5 你这条堆栈下一步最需要的两样东西

- **`libffmpeg_android.so` 的符号化 backtrace**（至少把 `#02 pc 0x24778` 与 `#03 pc 0x2803c` 映射到源码行）
- `avformat_open_input` 的返回值、输入 URI 类型（`content://` / `file://` / `http(s)://`）、以及你是否使用自定义 `AVIOContext`

做到这两点，基本就能把问题从“FFmpeg 崩了”收敛到“哪一个参数/回调/生命周期导致 `find_stream_info` 解引用空指针”。

