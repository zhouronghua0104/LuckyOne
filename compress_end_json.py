#!/usr/bin/env python3
"""
Compress the formatted JSON stored in the '端侧结果' column of the CSV file
so that the JSON occupies a single line.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Compress formatted JSON in the '端侧结果' column of a CSV file."
    )
    parser.add_argument(
        "input_csv",
        type=Path,
        help="Path to the input CSV file (e.g., export_舱内轮询.csv).",
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=None,
        help="Path to the output CSV file. Defaults to '<input>_compressed.csv'.",
    )
    return parser.parse_args()


def compress_json(text: str) -> str:
    if not text.strip():
        return text
    try:
        obj = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ValueError(f"无法解析端侧结果中的 JSON：{exc}") from exc
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def main() -> int:
    args = parse_args()
    if not args.input_csv.exists():
        print(f"输入文件不存在: {args.input_csv}", file=sys.stderr)
        return 1

    output_path = args.output or args.input_csv.with_name(
        f"{args.input_csv.stem}_compressed{args.input_csv.suffix}"
    )

    with args.input_csv.open("r", newline="", encoding="utf-8") as infile:
        reader = csv.DictReader(infile)
        fieldnames = reader.fieldnames
        if not fieldnames or "端侧结果" not in fieldnames:
            print("CSV 中找不到 '端侧结果' 列。", file=sys.stderr)
            return 1

        rows = []
        for idx, row in enumerate(reader, start=2):
            try:
                row["端侧结果"] = compress_json(row["端侧结果"])
            except ValueError as exc:
                print(f"第 {idx} 行处理失败: {exc}", file=sys.stderr)
                return 1
            rows.append(row)

    with output_path.open("w", newline="", encoding="utf-8") as outfile:
        writer = csv.DictWriter(outfile, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    print(f"处理完成，输出文件: {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
