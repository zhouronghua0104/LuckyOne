#!/usr/bin/env python3
"""
Camera Frame Extractor
----------------------
从本地摄像头（或视频流/文件）按固定时间间隔抽取帧并保存为图片。

示例：
  - 每5秒保存一张：
      python camera_frame_extractor.py --source 0 --interval 5 --output ./frames
  - 以目标帧率0.5fps保存（即每2秒一张）：
      python camera_frame_extractor.py --source 0 --target-fps 0.5 --output ./frames
  - 从RTSP流抽帧：
      python camera_frame_extractor.py --source rtsp://user:pass@host:554/stream --interval 10

依赖：opencv-python-headless
"""

import argparse
import os
import signal
import sys
import time
from datetime import datetime
from typing import Optional, Union

import cv2


def parse_source(source_str: str) -> Union[int, str]:
    """将输入的source解析为int设备号或字符串路径/URL。"""
    try:
        # 允许像 "0"、"1" 这样的数值字符串直接作为设备号
        return int(source_str)
    except ValueError:
        return source_str


def ensure_output_dir(output_dir: str) -> None:
    if not os.path.exists(output_dir):
        os.makedirs(output_dir, exist_ok=True)


def build_filename(output_dir: str, prefix: str, ext: str) -> str:
    now = datetime.now()
    # 例：cam_2025-09-23_14-05-07.123.jpg
    timestamp = now.strftime("%Y-%m-%d_%H-%M-%S.%f")[:-3]
    filename = f"{prefix}_{timestamp}.{ext}"
    return os.path.join(output_dir, filename)


def open_capture(source: Union[int, str]) -> cv2.VideoCapture:
    cap = cv2.VideoCapture(source)
    return cap


def configure_capture(
    cap: cv2.VideoCapture,
    width: Optional[int],
    height: Optional[int],
    fps: Optional[float],
) -> None:
    # 尽力设置，可能会因设备而无效
    if width is not None:
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, float(width))
    if height is not None:
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, float(height))
    if fps is not None and fps > 0:
        cap.set(cv2.CAP_PROP_FPS, float(fps))


def create_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="从摄像头/流按固定间隔抽帧保存图片"
    )
    parser.add_argument(
        "--source",
        type=str,
        default="0",
        help="视频源。整数设备号(如0)或路径/URL(如rtsp/http/文件). 默认0",
    )
    group = parser.add_mutually_exclusive_group(required=False)
    group.add_argument(
        "--interval",
        type=float,
        default=5.0,
        help="保存间隔(秒)。与--target-fps互斥。默认5秒",
    )
    group.add_argument(
        "--target-fps",
        type=float,
        default=None,
        help="目标保存帧率(每秒保存几张)。如0.5表示2秒/张",
    )
    parser.add_argument(
        "--output",
        type=str,
        default="./frames",
        help="输出目录，默认./frames",
    )
    parser.add_argument(
        "--prefix",
        type=str,
        default="cam",
        help="输出文件名前缀，默认cam",
    )
    parser.add_argument(
        "--ext",
        type=str,
        default="jpg",
        choices=["jpg", "png"],
        help="输出图片格式，默认jpg",
    )
    parser.add_argument(
        "--jpeg-quality",
        type=int,
        default=95,
        help="JPEG质量(0-100)，仅当ext=jpg时有效，默认95",
    )
    parser.add_argument(
        "--width",
        type=int,
        default=None,
        help="请求的捕获宽度(像素)",
    )
    parser.add_argument(
        "--height",
        type=int,
        default=None,
        help="请求的捕获高度(像素)",
    )
    parser.add_argument(
        "--capture-fps",
        type=float,
        default=None,
        help="请求的摄像头捕获帧率(可能不生效)",
    )
    parser.add_argument(
        "--max-frames",
        type=int,
        default=None,
        help="最多保存多少张，未设置则无限直到Ctrl+C",
    )
    parser.add_argument(
        "--max-seconds",
        type=float,
        default=None,
        help="最多运行多少秒，未设置则无限直到Ctrl+C",
    )
    return parser


class GracefulKiller:
    def __init__(self) -> None:
        self._stop = False
        signal.signal(signal.SIGINT, self._handle)
        signal.signal(signal.SIGTERM, self._handle)

    def _handle(self, signum, frame) -> None:  # type: ignore[no-redef]
        self._stop = True

    @property
    def stopped(self) -> bool:
        return self._stop


def main() -> int:
    parser = create_arg_parser()
    args = parser.parse_args()

    source = parse_source(args.source)
    save_interval = None
    if args.target_fps is not None and args.target_fps > 0:
        save_interval = 1.0 / args.target_fps
    else:
        if args.interval is None or args.interval <= 0:
            print("[Error] --interval 必须为正，或使用 --target-fps > 0", file=sys.stderr)
            return 2
        save_interval = float(args.interval)

    ensure_output_dir(args.output)

    cap = open_capture(source)
    if not cap.isOpened():
        print(f"[Error] 无法打开视频源: {args.source}", file=sys.stderr)
        return 1

    configure_capture(cap, args.width, args.height, args.capture_fps)

    # 打印实际分辨率/帧率（可能与请求不同）
    actual_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    actual_h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    actual_fps = cap.get(cv2.CAP_PROP_FPS)
    print(
        f"[Info] Capture opened: {args.source} | {actual_w}x{actual_h} @ {actual_fps:.2f}fps"
    )
    print(
        f"[Info] Save interval: {save_interval:.3f}s (prefix={args.prefix}, ext={args.ext})"
    )
    if args.max_frames is not None:
        print(f"[Info] Max frames: {args.max_frames}")
    if args.max_seconds is not None:
        print(f"[Info] Max seconds: {args.max_seconds}")

    killer = GracefulKiller()
    last_saved_time = 0.0
    saved_count = 0
    start_time = time.time()

    try:
        while True:
            if killer.stopped:
                print("\n[Info] 停止信号收到，准备退出...")
                break

            if args.max_frames is not None and saved_count >= args.max_frames:
                print("[Info] 已达到最大保存张数，退出。")
                break

            if args.max_seconds is not None and (time.time() - start_time) >= args.max_seconds:
                print("[Info] 已达到最大运行时长，退出。")
                break

            ok, frame = cap.read()
            if not ok or frame is None:
                # 读取失败，短暂休眠避免空转
                time.sleep(0.01)
                continue

            now = time.time()
            if now - last_saved_time >= save_interval:
                out_path = build_filename(args.output, args.prefix, args.ext)
                if args.ext == "jpg":
                    params = [int(cv2.IMWRITE_JPEG_QUALITY), int(args.jpeg_quality)]
                else:
                    params = []
                success = cv2.imwrite(out_path, frame, params)
                if success:
                    saved_count += 1
                    last_saved_time = now
                    print(f"[Saved] {out_path}")
                else:
                    print(f"[Warn] 保存失败: {out_path}")
            else:
                # 可选：微小休眠，降低CPU占用
                time.sleep(min(0.005, max(0.0, save_interval - (now - last_saved_time))))
    finally:
        cap.release()
        print(f"[Info] 结束，成功保存 {saved_count} 张。")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

