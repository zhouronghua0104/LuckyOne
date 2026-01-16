## FFmpeg API 抽帧与命令行 `-vf fps=3` 对齐

本仓库新增 `extract_video_with_time_range_aligned.cpp`，提供一个 **更接近** 命令行：

```bash
ffmpeg -i "$video" -vf "fps=3" -q:v 2 "xxx/frame_%05d.jpg"
```

的 API 抽帧实现思路：

- **只在 `start_time` seek 一次**，随后 **连续解码前进**（避免每秒 seek/flush 带来的 GOP/B 帧/重排序差异）。
- 使用 `av_frame_get_best_effort_timestamp()` 计算帧时间戳（更贴近 ffmpeg/滤镜链行为）。
- 维护 `next_target_time = start + n * interval`，每个目标时间只输出一张。
- 在相邻两帧时间区间内，对每个 target 用“就近(near)”策略选择 prev/cur，更接近 `fps` filter 的默认取整行为。

> 如果你希望做到 **完全等价**（字节级对齐行为），建议直接在代码里接入 libavfilter，构建 `buffer -> fps=3 -> buffersink` 的 filter graph（这与 CLI 同源）。

