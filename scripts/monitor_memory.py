#!/usr/bin/env python3
"""Utilities to monitor and plot memory usage for an Android app.

This script provides two subcommands:

1. collect: periodically samples memory statistics for a target app via
   ``adb shell dumpsys meminfo`` and stores the results in a CSV file.
2. plot: renders a time series chart of the collected memory usage metrics.

Example usage::

    # Collect memory data once per second for 5 minutes.
    python scripts/monitor_memory.py collect \
        --package com.crescent.myjnidemo \
        --interval 1 \
        --duration 300 \
        --output memory_usage.csv

    # Plot the previously collected data.
    python scripts/monitor_memory.py plot \
        --input memory_usage.csv \
        --output memory_usage.png

    The CSV file records timestamps in ISO-8601 format, elapsed seconds, and
    several key memory metrics (all in kilobytes) that are reported by
    ``dumpsys meminfo`` under the ``TOTAL`` row, along with system-wide memory
    usage derived from ``/proc/meminfo``.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import signal
import subprocess
import time
from pathlib import Path
from typing import Iterable, Optional


TOTAL_LINE_PREFIX = "TOTAL"
MEMINFO_KEYS = {
    "MemTotal": "system_total_kb",
    "MemFree": "system_free_kb",
    "Buffers": "system_buffers_kb",
    "Cached": "system_cached_kb",
    "MemAvailable": "system_available_kb",
}


class GracefulTerminator:
    """Helper to detect termination signals (Ctrl-C, SIGTERM)."""

    def __init__(self) -> None:
        self._terminate = False
        signal.signal(signal.SIGINT, self._handle)  # Ctrl-C
        signal.signal(signal.SIGTERM, self._handle)  # kill

    def _handle(self, signum, frame) -> None:  # type: ignore[override]
        del signum, frame
        self._terminate = True

    @property
    def should_terminate(self) -> bool:
        return self._terminate


def run_adb_command(args: Iterable[str]) -> str:
    """Runs an adb command and returns stdout as a string."""

    try:
        completed = subprocess.run(
            ["adb", *args],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
    except FileNotFoundError as exc:  # adb not found
        raise RuntimeError("adb executable not found. Ensure Android platform-tools are installed and adb is on PATH.") from exc
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(
            "adb command failed: "
            f"{' '.join(['adb', *args])}\nSTDERR: {exc.stderr.strip()}"
        ) from exc

    return completed.stdout


def fetch_meminfo(package: str) -> Optional[dict[str, int]]:
    """Fetches memory stats for the given package using dumpsys meminfo.

    Returns:
        A dict with keys corresponding to columns from the TOTAL row, or None
        if parsing fails.
    """

    output = run_adb_command(["shell", "dumpsys", "meminfo", package])
    for line in output.splitlines():
        line = line.strip()
        if line.startswith(TOTAL_LINE_PREFIX):
            parts = line.split()
            # Expected format:
            # TOTAL <pss> <private_dirty> <private_clean> <swapped_dirty> <heap_size> <heap_alloc> <heap_free>
            if len(parts) >= 8:
                try:
                    return {
                        "total_pss_kb": int(parts[1]),
                        "private_dirty_kb": int(parts[2]),
                        "private_clean_kb": int(parts[3]),
                        "swapped_dirty_kb": int(parts[4]),
                        "heap_size_kb": int(parts[5]),
                        "heap_alloc_kb": int(parts[6]),
                        "heap_free_kb": int(parts[7]),
                    }
                except ValueError:
                    return None
    return None


def fetch_system_meminfo() -> Optional[dict[str, int]]:
    """Fetches system-wide memory stats from /proc/meminfo."""

    output = run_adb_command(["shell", "cat", "/proc/meminfo"])
    values: dict[str, int] = {}
    for line in output.splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[0].rstrip(":") in MEMINFO_KEYS:
            key = parts[0].rstrip(":")
            try:
                values[MEMINFO_KEYS[key]] = int(parts[1])
            except ValueError:
                return None

    if not values:
        return None

    if "system_total_kb" in values and "system_free_kb" in values:
        values["system_used_kb"] = values["system_total_kb"] - values["system_free_kb"]

    return values


def collect_memory_samples(
    package: str,
    interval: float,
    output_csv: Path,
    duration: Optional[float],
) -> None:
    """Collects memory samples and writes them to *output_csv*."""

    output_csv.parent.mkdir(parents=True, exist_ok=True)
    start_ts = time.monotonic()
    start_wall = dt.datetime.now(dt.timezone.utc)
    terminator = GracefulTerminator()

    fieldnames = [
        "timestamp_utc",
        "elapsed_seconds",
        "total_pss_kb",
        "private_dirty_kb",
        "private_clean_kb",
        "swapped_dirty_kb",
        "heap_size_kb",
        "heap_alloc_kb",
        "heap_free_kb",
        "system_total_kb",
        "system_free_kb",
        "system_buffers_kb",
        "system_cached_kb",
        "system_available_kb",
        "system_used_kb",
    ]

    with output_csv.open("w", newline="") as csvfile:
        writer = csv.DictWriter(csvfile, fieldnames=fieldnames)
        writer.writeheader()

        while True:
            if terminator.should_terminate:
                print("Termination signal received. Stopping collection.")
                break

            elapsed = time.monotonic() - start_ts
            if duration is not None and elapsed > duration:
                print("Duration reached. Stopping collection.")
                break

            meminfo = fetch_meminfo(package)
            system_mem = fetch_system_meminfo()
            timestamp = start_wall + dt.timedelta(seconds=elapsed)
            iso_ts = timestamp.isoformat()

            row = {
                "timestamp_utc": iso_ts,
                "elapsed_seconds": f"{elapsed:.3f}",
                "total_pss_kb": "",
                "private_dirty_kb": "",
                "private_clean_kb": "",
                "swapped_dirty_kb": "",
                "heap_size_kb": "",
                "heap_alloc_kb": "",
                "heap_free_kb": "",
                "system_total_kb": "",
                "system_free_kb": "",
                "system_buffers_kb": "",
                "system_cached_kb": "",
                "system_available_kb": "",
                "system_used_kb": "",
            }

            if meminfo is None:
                print("Warning: Failed to parse meminfo output for app. App columns left blank.")
            else:
                row.update(meminfo)

            if system_mem is None:
                print("Warning: Failed to read system memory info. System columns left blank.")
            else:
                row.update(system_mem)

            writer.writerow(row)

            csvfile.flush()
            time.sleep(interval)


def plot_memory_curve(input_csv: Path, output_image: Optional[Path]) -> None:
    """Generates a memory usage plot from the CSV data."""

    try:
        import matplotlib.pyplot as plt
    except ImportError as exc:
        raise RuntimeError(
            "matplotlib is required for plotting. Install it with 'pip install matplotlib'."
        ) from exc

    timestamps: list[float] = []
    total_pss: list[float] = []
    heap_alloc: list[float] = []
    system_used: list[float] = []

    with input_csv.open("r", newline="") as csvfile:
        reader = csv.DictReader(csvfile)
        for row in reader:
            try:
                elapsed = float(row["elapsed_seconds"])
            except (KeyError, TypeError, ValueError):
                continue

            timestamps.append(elapsed)

            def parse_field(key: str) -> Optional[float]:
                value = row.get(key)
                if value is None or value == "":
                    return None
                try:
                    return float(value)
                except ValueError:
                    return None

            total_val = parse_field("total_pss_kb")
            heap_alloc_val = parse_field("heap_alloc_kb")

            total_pss.append(total_val if total_val is not None else float("nan"))
            heap_alloc.append(heap_alloc_val if heap_alloc_val is not None else float("nan"))

            system_used_val = parse_field("system_used_kb")
            system_used.append(system_used_val if system_used_val is not None else float("nan"))

    if not timestamps:
        raise RuntimeError("No valid data rows found in CSV. Cannot plot.")

    plt.figure(figsize=(10, 6))
    plt.plot(timestamps, total_pss, label="Total PSS (KB)", linewidth=2)
    plt.plot(timestamps, heap_alloc, label="Heap Alloc (KB)", linewidth=1.5, linestyle="--")
    plt.plot(timestamps, system_used, label="System Used (KB)", linewidth=1.5, linestyle=":")
    plt.title("Inference Memory Usage Over Time")
    plt.xlabel("Elapsed Time (s)")
    plt.ylabel("Memory (KB)")
    plt.legend()
    plt.grid(True, linestyle=":", linewidth=0.5)
    plt.tight_layout()

    if output_image is not None:
        output_image.parent.mkdir(parents=True, exist_ok=True)
        plt.savefig(output_image)
        print(f"Plot saved to {output_image}")
    else:
        plt.show()


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Monitor and plot Android app memory usage via adb.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    collect_parser = subparsers.add_parser("collect", help="Collect memory usage samples into a CSV file.")
    collect_parser.add_argument("--package", required=True, help="Target Android package name.")
    collect_parser.add_argument(
        "--interval",
        type=float,
        default=1.0,
        help="Sampling interval in seconds (default: 1.0).",
    )
    collect_parser.add_argument(
        "--duration",
        type=float,
        default=None,
        help="Optional duration in seconds to limit collection. If omitted, runs until interrupted.",
    )
    collect_parser.add_argument(
        "--output",
        type=Path,
        default=Path("memory_usage.csv"),
        help="Path to the CSV output file (default: memory_usage.csv).",
    )

    plot_parser = subparsers.add_parser("plot", help="Plot memory usage from a CSV file.")
    plot_parser.add_argument("--input", type=Path, required=True, help="Path to the CSV data file.")
    plot_parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="Optional path to save the plot image. If omitted, displays interactively.",
    )

    return parser


def main(argv: Optional[list[str]] = None) -> None:
    parser = build_argument_parser()
    args = parser.parse_args(argv)

    if args.command == "collect":
        collect_memory_samples(
            package=args.package,
            interval=args.interval,
            output_csv=args.output,
            duration=args.duration,
        )
    elif args.command == "plot":
        plot_memory_curve(input_csv=args.input, output_image=args.output)
    else:
        parser.error(f"Unknown command: {args.command}")


if __name__ == "__main__":
    main()
