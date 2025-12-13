#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""android_process_cpu_monitor.py

Monitor per-CPU-core CPU usage of a target Android process via ADB.

It periodically samples /proc/<pid>/task/<tid>/stat, computes per-thread CPU time
(delta utime+stime), and attributes the delta to the CPU core that thread last ran on
(field `processor`). This provides a practical approximation of "process per-core CPU".

Outputs:
- CSV with columns: timestamp_iso, elapsed_s, pid, total_percent, cpu0_percent..cpuN_percent
- PNG plot saved next to CSV (same basename + .png)

Usage:
  python3 tools/android_process_cpu_monitor.py --package com.example.app --interval 1 --duration 60 --output out.csv

Notes:
- Requires `adb` available on PATH and a connected device (or emulator).
- If matplotlib is not installed, CSV collection still works; plotting will be skipped with guidance.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple


@dataclass
class Sample:
    timestamp_iso: str
    elapsed_s: float
    pid: int
    total_percent: float
    per_cpu_percent: List[float]


def _run_adb_shell(cmd: str, *, serial: Optional[str] = None, timeout_s: float = 10.0) -> str:
    adb_cmd = ["adb"]
    if serial:
        adb_cmd += ["-s", serial]
    adb_cmd += ["shell", cmd]

    try:
        p = subprocess.run(
            adb_cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=timeout_s,
            check=False,
        )
    except FileNotFoundError as e:
        raise RuntimeError("adb not found. Please install Android platform-tools and ensure `adb` is on PATH.") from e

    if p.returncode != 0:
        raise RuntimeError(f"adb shell failed (code={p.returncode}): {p.stderr.strip() or p.stdout.strip()}")

    return p.stdout.strip()


def _get_pid(package: str, *, serial: Optional[str] = None) -> Optional[int]:
    # Prefer pidof, fallback to ps parsing.
    out = _run_adb_shell(f"pidof {package}", serial=serial)
    out = out.strip()
    if out:
        # pidof may return multiple pids; take the first.
        first = out.split()[0]
        if first.isdigit():
            return int(first)

    # Fallback: ps -A | grep package
    # Use toybox `ps` formatting variance: find a numeric field that is PID.
    out = _run_adb_shell(f"ps -A | grep -F {package}", serial=serial)
    lines = [ln for ln in out.splitlines() if ln.strip()]
    if not lines:
        return None

    # Try common formats: USER PID ... NAME
    for ln in lines:
        parts = ln.split()
        for i, tok in enumerate(parts):
            if tok.isdigit() and i > 0:
                # Heuristic: second column often PID
                return int(tok)

    return None


def _get_cpu_count(*, serial: Optional[str] = None) -> int:
    # Most reliable: count cpuX directories.
    out = _run_adb_shell("ls -d /sys/devices/system/cpu/cpu[0-9]* 2>/dev/null | wc -l", serial=serial)
    out = out.strip()
    if out.isdigit():
        n = int(out)
        if n > 0:
            return n

    out = _run_adb_shell("nproc 2>/dev/null || cat /proc/cpuinfo | grep -c '^processor'", serial=serial)
    out = out.strip()
    if out.isdigit() and int(out) > 0:
        return int(out)

    return 1


def _get_clk_tck(*, serial: Optional[str] = None) -> int:
    out = _run_adb_shell("getconf CLK_TCK 2>/dev/null", serial=serial)
    out = out.strip()
    if out.isdigit() and int(out) > 0:
        return int(out)

    # Common Android default.
    return 100


_STAT_RE = re.compile(r"^(\d+)\s+\((.*)\)\s+(.*)$")


def _parse_proc_stat_line(stat_line: str) -> Tuple[int, int, int]:
    """Return (utime, stime, processor) from /proc/.../stat line."""
    m = _STAT_RE.match(stat_line.strip())
    if not m:
        raise ValueError("unexpected /proc stat format")

    # pid = int(m.group(1))
    rest = m.group(3)
    toks = rest.split()
    # Field mapping: after comm, toks[0] is state(field3).
    # utime=field14 => toks[11], stime=field15 => toks[12], processor=field39 => toks[36]
    utime = int(toks[11])
    stime = int(toks[12])
    processor = int(toks[36]) if len(toks) > 36 else -1
    return utime, stime, processor


def _list_tids(pid: int, *, serial: Optional[str] = None) -> List[int]:
    out = _run_adb_shell(f"ls /proc/{pid}/task 2>/dev/null", serial=serial)
    tids: List[int] = []
    for tok in out.split():
        if tok.isdigit():
            tids.append(int(tok))
    return tids


def _read_thread_stat(pid: int, tid: int, *, serial: Optional[str] = None) -> Optional[Tuple[int, int, int]]:
    out = _run_adb_shell(f"cat /proc/{pid}/task/{tid}/stat 2>/dev/null", serial=serial)
    out = out.strip()
    if not out:
        return None
    try:
        return _parse_proc_stat_line(out)
    except Exception:
        return None


def _now_iso() -> str:
    return dt.datetime.now().astimezone().isoformat(timespec="seconds")


def _default_output_path(package: str) -> str:
    ts = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
    safe = re.sub(r"[^A-Za-z0-9_.-]+", "_", package)
    return os.path.abspath(f"cpu_{safe}_{ts}.csv")


def _plot_csv(csv_path: str, png_path: str) -> None:
    try:
        import matplotlib

        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except Exception:
        print(
            "[plot] matplotlib not available; skip plotting. Install it with: pip install matplotlib",
            file=sys.stderr,
        )
        return

    xs: List[float] = []
    per_cpu_series: List[List[float]] = []

    with open(csv_path, "r", newline="") as f:
        reader = csv.DictReader(f)
        cpu_cols = [c for c in (reader.fieldnames or []) if c.startswith("cpu") and c.endswith("_percent")]
        cpu_cols.sort(key=lambda s: int(re.findall(r"\d+", s)[0]) if re.findall(r"\d+", s) else 0)

        for row in reader:
            xs.append(float(row["elapsed_s"]))
            if not per_cpu_series:
                per_cpu_series = [[] for _ in cpu_cols]
            for i, c in enumerate(cpu_cols):
                per_cpu_series[i].append(float(row[c]))

    if not xs or not per_cpu_series:
        print("[plot] no data to plot", file=sys.stderr)
        return

    plt.figure(figsize=(12, 6))
    for i, ys in enumerate(per_cpu_series):
        plt.plot(xs, ys, label=f"CPU{i}", linewidth=1.5)

    plt.title("Process CPU usage per core")
    plt.xlabel("Elapsed (s)")
    plt.ylabel("CPU %")
    plt.grid(True, linestyle="--", alpha=0.4)
    plt.legend(ncol=4, fontsize=9)
    plt.tight_layout()
    plt.savefig(png_path, dpi=160)


def monitor(*, package: str, interval_s: float, duration_s: Optional[float], output_path: str, serial: Optional[str] = None) -> Tuple[str, str]:
    pid = _get_pid(package, serial=serial)
    if pid is None:
        raise RuntimeError(f"process not found for package: {package}")

    cpu_count = _get_cpu_count(serial=serial)
    clk_tck = _get_clk_tck(serial=serial)

    out_csv = os.path.abspath(output_path)
    os.makedirs(os.path.dirname(out_csv) or ".", exist_ok=True)
    out_png = os.path.splitext(out_csv)[0] + ".png"

    fieldnames = ["timestamp_iso", "elapsed_s", "pid", "total_percent"] + [
        f"cpu{i}_percent" for i in range(cpu_count)
    ]

    prev_thread_times: Dict[int, int] = {}

    start_mono = time.monotonic()
    last_mono = start_mono

    print(f"[info] package={package} pid={pid} cpus={cpu_count} clk_tck={clk_tck}")
    print(f"[info] writing CSV: {out_csv}")
    print("[info] press CTRL+C to stop")

    with open(out_csv, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()

        try:
            while True:
                now_mono = time.monotonic()
                elapsed = now_mono - start_mono
                dt_s = now_mono - last_mono
                if dt_s <= 0:
                    dt_s = interval_s

                if duration_s is not None and elapsed > duration_s:
                    break

                # Refresh PID in case of process restart.
                new_pid = _get_pid(package, serial=serial)
                if new_pid is None:
                    raise RuntimeError(f"process disappeared for package: {package}")
                if new_pid != pid:
                    print(f"[warn] pid changed {pid} -> {new_pid}; reset baseline")
                    pid = new_pid
                    prev_thread_times.clear()

                per_cpu_jiffies = [0] * cpu_count
                total_jiffies = 0

                tids = _list_tids(pid, serial=serial)
                for tid in tids:
                    stat = _read_thread_stat(pid, tid, serial=serial)
                    if not stat:
                        continue
                    utime, stime, cpu = stat
                    t = utime + stime
                    prev = prev_thread_times.get(tid)
                    prev_thread_times[tid] = t
                    if prev is None:
                        continue
                    delta = t - prev
                    if delta <= 0:
                        continue

                    total_jiffies += delta
                    if 0 <= cpu < cpu_count:
                        per_cpu_jiffies[cpu] += delta

                # Convert to percents.
                total_sec = total_jiffies / float(clk_tck)
                total_percent = (total_sec / dt_s) * 100.0
                per_cpu_percent = [((j / float(clk_tck)) / dt_s) * 100.0 for j in per_cpu_jiffies]

                row = {
                    "timestamp_iso": _now_iso(),
                    "elapsed_s": f"{elapsed:.3f}",
                    "pid": str(pid),
                    "total_percent": f"{total_percent:.2f}",
                }
                for i, v in enumerate(per_cpu_percent):
                    row[f"cpu{i}_percent"] = f"{v:.2f}"

                writer.writerow(row)
                f.flush()

                last_mono = now_mono

                # Sleep until next interval.
                if interval_s > 0:
                    time.sleep(interval_s)

        except KeyboardInterrupt:
            print("\n[info] stopped by user")

    print(f"[info] plotting PNG: {out_png}")
    _plot_csv(out_csv, out_png)
    print("[done]")

    return out_csv, out_png


def main(argv: Optional[List[str]] = None) -> int:
    p = argparse.ArgumentParser(description="Monitor Android process per-core CPU usage via ADB")
    p.add_argument("--package", required=True, help="Target package name, e.g. com.example.app")
    p.add_argument("--interval", type=float, default=1.0, help="Sampling interval in seconds")
    p.add_argument("--duration", type=float, default=None, help="Total duration in seconds; omit to run until CTRL+C")
    p.add_argument("--output", default=None, help="Output CSV file path")
    p.add_argument("--serial", default=None, help="ADB device serial (optional)")

    args = p.parse_args(argv)

    output = args.output or _default_output_path(args.package)

    if args.interval <= 0:
        print("--interval must be > 0", file=sys.stderr)
        return 2
    if args.duration is not None and args.duration <= 0:
        print("--duration must be > 0", file=sys.stderr)
        return 2

    try:
        monitor(
            package=args.package,
            interval_s=args.interval,
            duration_s=args.duration,
            output_path=output,
            serial=args.serial,
        )
    except Exception as e:
        print(f"[error] {e}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
