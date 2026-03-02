#!/usr/bin/env python3
"""批量执行 maptest 推理任务。"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

MODEL_SERVICE = "com.svw.modelservice/.ModelService"
DEFAULT_SLEEP_SECONDS = 3.0


def info(message: str) -> None:
    print(message, flush=True)


def error(message: str) -> None:
    print(message, file=sys.stderr, flush=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "读取 JSON 测试集中的 intent，逐条执行:\n"
            "adb shell dumpsys activity service "
            "com.svw.modelservice/.ModelService inference maptest [intent]"
        )
    )
    parser.add_argument("json_file", help="测试集 JSON 文件路径")
    parser.add_argument(
        "--sleep-seconds",
        type=float,
        default=DEFAULT_SLEEP_SECONDS,
        help=f"每个测试项之间休眠秒数，默认 {int(DEFAULT_SLEEP_SECONDS)} 秒",
    )
    return parser.parse_args()


def load_intents(json_file: Path) -> list[str]:
    try:
        data = json.loads(json_file.read_text(encoding="utf-8"))
    except OSError as exc:
        raise RuntimeError(f"读取文件失败: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"JSON 解析失败: {exc}") from exc

    if not isinstance(data, list):
        raise RuntimeError("输入 JSON 根节点必须是数组。")

    intents: list[str] = []
    for index, item in enumerate(data, start=1):
        if not isinstance(item, dict):
            raise RuntimeError(f"第 {index} 项不是对象。")
        intent = item.get("intent")
        if not isinstance(intent, str):
            raise RuntimeError(f"第 {index} 项缺少字符串类型 intent 字段。")
        intents.append(intent)
    return intents


def run_one_inference(intent: str) -> int:
    command = [
        "adb",
        "shell",
        "dumpsys",
        "activity",
        "service",
        MODEL_SERVICE,
        "inference",
        "maptest",
        intent,
    ]
    try:
        completed = subprocess.run(command, check=False)
    except OSError as exc:
        error(f"执行 adb 失败: {exc}")
        return 1
    return completed.returncode


def main() -> int:
    args = parse_args()

    json_file = Path(args.json_file)
    if not json_file.is_file():
        error(f"错误: 文件不存在: {json_file}")
        return 1

    if shutil.which("adb") is None:
        error("错误: 未检测到 adb，请先安装 Android platform-tools。")
        return 1

    if args.sleep_seconds < 0:
        error("错误: --sleep-seconds 不能为负数。")
        return 1

    try:
        intents = load_intents(json_file)
    except RuntimeError as exc:
        error(f"错误: {exc}")
        return 1

    total = len(intents)
    if total == 0:
        info("任务执行完成: 测试项总数 0。")
        return 0

    success_count = 0
    failed_count = 0

    info(f"开始执行 maptest，测试项总数: {total}")
    for index, intent in enumerate(intents, start=1):
        info(f"[{index}/{total}] intent: {intent}")
        result_code = run_one_inference(intent)
        if result_code == 0:
            success_count += 1
        else:
            failed_count += 1
            error(f"[{index}/{total}] 执行失败，继续下一条。")

        if index < total and args.sleep_seconds > 0:
            time.sleep(args.sleep_seconds)

    info(
        f"任务执行完成: 共执行 {total} 个测试项，"
        f"成功 {success_count} 个，失败 {failed_count} 个。"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
