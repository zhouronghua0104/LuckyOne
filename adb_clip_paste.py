#!/usr/bin/env python3
"""adb clipboard set + paste helper.

Usage examples:
  - Focus the input box on the device first, then:
      ./adb_clip_paste.py "hello world"

  - Read from a file:
      ./adb_clip_paste.py --file text.txt

  - Read from stdin:
      printf 'multi\nline' | ./adb_clip_paste.py --stdin

Notes:
  - This script uses clipboard + KEYCODE_PASTE (279).
  - Clipboard setting prefers: `cmd clipboard set` (Android 10+ typical).
    Fallback: `service call clipboard ...` (may not work on all ROMs).
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import time
from typing import List, Optional, Tuple


KEYCODE_PASTE = 279


class AdbError(RuntimeError):
    pass


def _run(cmd: List[str], *, input_text: Optional[str] = None) -> subprocess.CompletedProcess:
    return subprocess.run(
        cmd,
        input=input_text,
        text=True,
        capture_output=True,
    )


def _adb_base(serial: Optional[str]) -> List[str]:
    base = ["adb"]
    if serial:
        base += ["-s", serial]
    return base


def _adb(serial: Optional[str], args: List[str], *, input_text: Optional[str] = None) -> subprocess.CompletedProcess:
    return _run(_adb_base(serial) + args, input_text=input_text)


def _connected_devices() -> List[str]:
    p = _run(["adb", "devices"])
    if p.returncode != 0:
        raise AdbError((p.stderr or p.stdout or "").strip() or "failed to run adb devices")

    devices: List[str] = []
    for line in p.stdout.splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices"):
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return devices


def _resolve_serial(requested: Optional[str]) -> Optional[str]:
    if requested:
        return requested

    devices = _connected_devices()
    if not devices:
        raise AdbError("no adb device found (run: adb devices)")
    if len(devices) > 1:
        raise AdbError(
            "multiple adb devices found; please specify one with --serial\n"
            + "\n".join(f"- {d}" for d in devices)
        )
    return devices[0]


def _try_cmd_clipboard_set(serial: Optional[str], text: str) -> Tuple[bool, str]:
    # `adb shell cmd clipboard set <text>`
    p = _adb(serial, ["shell", "cmd", "clipboard", "set", text])
    if p.returncode == 0:
        return True, ""

    err = (p.stderr or p.stdout or "").strip()
    return False, err


def _try_service_call_clipboard_set(serial: Optional[str], text: str) -> Tuple[bool, str]:
    # Transaction codes differ across Android versions/ROMs.
    # We try a couple of common variants.
    #
    # Examples seen in the wild:
    # - service call clipboard 1 i32 0 s16 "text"
    # - service call clipboard 2 i32 0 s16 "text"
    candidates = [
        ["shell", "service", "call", "clipboard", "2", "i32", "0", "s16", text],
        ["shell", "service", "call", "clipboard", "1", "i32", "0", "s16", text],
    ]

    last_err = ""
    for args in candidates:
        p = _adb(serial, args)
        out = (p.stdout or "") + (p.stderr or "")
        out = out.strip()
        if p.returncode == 0 and ("Exception" not in out):
            return True, ""
        last_err = out or f"service call failed: {' '.join(args)}"

    return False, last_err


def _set_clipboard(serial: Optional[str], text: str) -> None:
    ok, err = _try_cmd_clipboard_set(serial, text)
    if ok:
        return

    ok2, err2 = _try_service_call_clipboard_set(serial, text)
    if ok2:
        return

    raise AdbError(
        "failed to set clipboard via adb. Tried:\n"
        "- cmd clipboard set\n"
        "- service call clipboard (common transactions)\n\n"
        "Last errors:\n"
        f"cmd clipboard set: {err or '(no output)'}\n"
        f"service call clipboard: {err2 or '(no output)'}\n\n"
        "Tip: ensure USB debugging is enabled, and consider using `adb shell input text` as a fallback on very old ROMs."
    )


def _paste(serial: Optional[str], *, longpress: bool) -> None:
    args = ["shell", "input", "keyevent"]
    if longpress:
        args.append("--longpress")
    args.append(str(KEYCODE_PASTE))

    p = _adb(serial, args)
    if p.returncode != 0:
        raise AdbError((p.stderr or p.stdout or "").strip() or "failed to send paste keyevent")


def _read_text(args: argparse.Namespace) -> str:
    if args.stdin:
        return sys.stdin.read()
    if args.file:
        with open(args.file, "r", encoding="utf-8") as f:
            return f.read()
    if args.text is not None:
        return args.text
    raise AdbError("no input text; pass TEXT, or --file, or --stdin")


def main() -> int:
    parser = argparse.ArgumentParser(description="Set clipboard then paste into focused input via adb.")
    parser.add_argument("text", nargs="?", help="text to paste")
    parser.add_argument("--file", help="read text from file (utf-8)")
    parser.add_argument("--stdin", action="store_true", help="read text from stdin")
    parser.add_argument("--serial", help="adb device serial (when multiple devices are connected)")
    parser.add_argument(
        "--delay-ms",
        type=int,
        default=150,
        help="delay between setting clipboard and pasting (default: 150ms)",
    )
    parser.add_argument(
        "--longpress",
        action="store_true",
        help="use long-press keyevent for paste (some OEM ROMs need this)",
    )

    ns = parser.parse_args()

    try:
        serial = _resolve_serial(ns.serial)
        text = _read_text(ns)
        _set_clipboard(serial, text)
        if ns.delay_ms > 0:
            time.sleep(ns.delay_ms / 1000.0)
        _paste(serial, longpress=ns.longpress)
        return 0
    except AdbError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
