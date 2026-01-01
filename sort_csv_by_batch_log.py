#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Sort a CSV file's rows by the filename order defined in batch.log.

Assumptions:
- The CSV is row-based: first row is header, each subsequent row is one record.
- One column contains the filename, default header name: "文件名称（输入）"
- batch.log contains one filename per line (empty lines are ignored).

Default behavior:
- Rows whose filename is NOT in batch.log are appended to the end in original order.

Examples:
  python3 sort_csv_by_batch_log.py \
    --input-csv "export_哨兵_第四批.csv" \
    --batch-log "batch.log" \
    --output-csv "export_哨兵_第四批.sorted.csv"
"""

from __future__ import annotations

import argparse
import csv
from pathlib import Path
from typing import List, Tuple


def _read_batch_order(batch_log_path: Path) -> dict[str, int]:
    order: dict[str, int] = {}
    idx = 0
    with batch_log_path.open("r", encoding="utf-8", errors="replace") as f:
        for raw in f:
            name = raw.strip()
            if not name:
                continue
            if name.startswith("#"):
                continue
            if name not in order:
                order[name] = idx
                idx += 1
    return order


def _find_filename_col(header: List[str], explicit: str | None) -> int:
    if explicit:
        if explicit in header:
            return header.index(explicit)
        raise SystemExit(
            f'Cannot find filename column "{explicit}" in CSV header: {header}'
        )

    preferred = [
        "文件名称（输入）",
        "文件名称(输入)",
        "文件名称",
        "文件名",
        "filename",
        "file_name",
    ]
    for col in preferred:
        if col in header:
            return header.index(col)

    # Fuzzy fallback: first column whose header contains 文件名/文件名称
    for i, col in enumerate(header):
        if ("文件名" in col) or ("文件名称" in col):
            return i

    raise SystemExit(
        "Cannot detect filename column. "
        "Please pass --filename-column with the exact header text."
    )


def _read_csv_rows(path: Path) -> Tuple[List[str], List[List[str]]]:
    # utf-8-sig to tolerate BOM from Excel exports
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.reader(f)
        rows = list(reader)

    if not rows:
        raise SystemExit(f"CSV is empty: {path}")
    header = rows[0]
    data = rows[1:]
    return header, data


def _write_csv(path: Path, header: List[str], rows: List[List[str]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(
            f,
            quoting=csv.QUOTE_ALL,
            lineterminator="\n",
        )
        writer.writerow(header)
        writer.writerows(rows)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Sort CSV rows by the filename order in batch.log."
    )
    parser.add_argument(
        "--input-csv",
        default="export_哨兵_第四批.csv",
        help='Input CSV path (default: "export_哨兵_第四批.csv")',
    )
    parser.add_argument(
        "--batch-log",
        default="batch.log",
        help='batch.log path (default: "batch.log")',
    )
    parser.add_argument(
        "--output-csv",
        default="export_哨兵_第四批.sorted.csv",
        help='Output CSV path (default: "export_哨兵_第四批.sorted.csv")',
    )
    parser.add_argument(
        "--filename-column",
        default=None,
        help='Exact header text for the filename column (e.g. "文件名称（输入）")',
    )
    parser.add_argument(
        "--unmatched",
        choices=["keep_end", "drop"],
        default="keep_end",
        help='How to handle rows whose filename is not in batch.log (default: keep_end)',
    )

    args = parser.parse_args()

    input_csv = Path(args.input_csv)
    batch_log = Path(args.batch_log)
    output_csv = Path(args.output_csv)

    if not input_csv.exists():
        raise SystemExit(f"Input CSV not found: {input_csv}")
    if not batch_log.exists():
        raise SystemExit(f"batch.log not found: {batch_log}")

    order = _read_batch_order(batch_log)
    header, data = _read_csv_rows(input_csv)
    filename_col = _find_filename_col(header, args.filename_column)

    indexed: List[Tuple[Tuple[int, int, int], List[str]]] = []
    unmatched_count = 0
    for original_pos, row in enumerate(data):
        filename = row[filename_col].strip() if filename_col < len(row) else ""
        if filename in order:
            sort_key = (0, order[filename], original_pos)
        else:
            unmatched_count += 1
            if args.unmatched == "drop":
                continue
            sort_key = (1, original_pos, original_pos)
        indexed.append((sort_key, row))

    indexed.sort(key=lambda x: x[0])
    sorted_rows = [r for _, r in indexed]

    _write_csv(output_csv, header, sorted_rows)

    print(f"Input rows (excluding header): {len(data)}")
    print(f"batch.log filenames: {len(order)}")
    print(f"Unmatched rows: {unmatched_count} ({args.unmatched})")
    print(f"Output rows (excluding header): {len(sorted_rows)}")
    print(f"Wrote: {output_csv}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

