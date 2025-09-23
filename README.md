## 摄像头抽帧保存图片

使用 Python/OpenCV 或 ffmpeg 从摄像头/视频流按固定间隔抽取帧保存为图片。

### 运行环境
- Python 3.8+
- Linux/macOS/Windows 均可（本仓库示例在 Linux）

### 安装依赖
```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
```

### 使用（Python 脚本）
```bash
# 每5秒保存一张（默认摄像头0）
python camera_frame_extractor.py --source 0 --interval 5 --output ./frames

# 以目标帧率0.5fps保存（即每2秒一张）
python camera_frame_extractor.py --source 0 --target-fps 0.5 --output ./frames

# 指定分辨率与请求捕获帧率（是否生效取决于设备/驱动）
python camera_frame_extractor.py --source 0 --interval 3 --width 1280 --height 720 --capture-fps 30

# 从RTSP流保存
python camera_frame_extractor.py --source rtsp://user:pass@host:554/stream --interval 10

# 只保存10张或在60秒内退出（先到先停）
python camera_frame_extractor.py --source 0 --interval 5 --max-frames 10 --max-seconds 60
```

主要参数：
- `--source`: 设备号（如`0`）或URL/文件路径（RTSP/HTTP/文件）
- `--interval`: 两次保存之间的秒数（与`--target-fps`二选一）
- `--target-fps`: 目标保存帧率，例如0.5表示2秒保存一张
- `--output`: 输出目录
- `--prefix`: 文件名前缀，默认`cam`
- `--ext`: `jpg` 或 `png`
- `--jpeg-quality`: 仅对jpg有效
- `--width/--height`: 请求捕获分辨率
- `--capture-fps`: 请求捕获帧率
- `--max-frames / --max-seconds`: 终止条件

输出文件示例：`cam_2025-09-23_14-05-07.123.jpg`

### ffmpeg 一行命令替代
无需Python，也可直接用 ffmpeg 抽帧保存：
```bash
# 每5秒保存一张（摄像头设备可能在Linux为 /dev/video0）
ffmpeg -y -f v4l2 -i /dev/video0 -vf fps=1/5 frames/out_%Y-%m-%d_%H-%M-%S.jpg

# 从RTSP流每2秒保存一张
ffmpeg -y -rtsp_transport tcp -i "rtsp://user:pass@host:554/stream" -vf fps=1/2 frames/rtsp_%Y-%m-%d_%H-%M-%S.jpg
```

提示：
- 若目录不存在请先创建：`mkdir -p frames`
- Windows 摄像头可尝试 `-f dshow -i video="你的摄像头名称"`。
- 复杂网络流可加速或稳定参数，例如 `-rtsp_transport tcp`、`-rw_timeout` 等。

