#!/usr/bin/env python3
import argparse
import json
import sys
from pathlib import Path


def parse_loose_array(text: str):
    i = 0
    n = len(text)

    def skip_ws():
        nonlocal i
        while i < n and text[i].isspace():
            i += 1

    def parse_list():
        nonlocal i
        skip_ws()
        if i >= n or text[i] != "[":
            raise ValueError("expected '['")
        i += 1
        result = []
        while True:
            skip_ws()
            if i >= n:
                raise ValueError("unclosed '['")
            if text[i] == "]":
                i += 1
                return result
            if text[i] == ",":
                i += 1
                continue
            if text[i] == "[":
                result.append(parse_list())
                continue
            start = i
            while i < n and text[i] not in "[],":
                i += 1
            token = text[start:i].strip()
            if token:
                result.append(token)

    parsed = parse_list()
    skip_ws()
    if i != n:
        raise ValueError("unexpected trailing content")
    return parsed


def parse_line(line: str):
    stripped = line.strip()
    if not stripped:
        return None
    try:
        return json.loads(stripped)
    except json.JSONDecodeError:
        return parse_loose_array(stripped)


def merge_sequence(seq):
    positions = {}
    order = []
    for idx, action in enumerate(seq, start=1):
        if action in {"X", "其他", "其它"}:
            continue
        if action not in positions:
            positions[action] = []
            order.append(action)
        positions[action].append(idx)
    return {action: positions[action] for action in order}


def merge_data(data):
    if isinstance(data, list):
        if all(isinstance(item, list) for item in data):
            return [merge_sequence(item) for item in data]
        if any(isinstance(item, list) for item in data):
            raise ValueError("mixed list contents; expected all lists or all actions")
        return merge_sequence(data)
    raise ValueError("expected JSON array")


def output_path_for(input_path: str) -> str:
    path = Path(input_path)
    if path.suffix:
        return str(path.with_name(f"{path.stem}_merge{path.suffix}"))
    return str(path.with_name(f"{path.name}_merge"))


def main():
    parser = argparse.ArgumentParser(
        description="Merge action arrays by action key and index positions."
    )
    parser.add_argument("input_file", help="Path to the input file.")
    parser.add_argument(
        "output_file",
        nargs="?",
        default=None,
        help="Path to the output file (defaults to *_merge).",
    )
    args = parser.parse_args()

    output_path = args.output_file or output_path_for(args.input_file)

    try:
        with open(args.input_file, "r", encoding="utf-8") as f_in, open(
            output_path, "w", encoding="utf-8"
        ) as f_out:
            for line_no, line in enumerate(f_in, start=1):
                if not line.strip():
                    f_out.write("\n")
                    continue
                try:
                    data = parse_line(line)
                    merged = merge_data(data)
                except Exception as exc:
                    raise ValueError(f"failed to parse line {line_no}: {exc}") from exc
                f_out.write(
                    json.dumps(merged, ensure_ascii=False, separators=(",", ":"))
                )
                f_out.write("\n")
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
