#!/usr/bin/env bash
set -euo pipefail

# Collect top snapshots every second and write to a log file.
# Runs until Ctrl+C is pressed.
# Usage: ./top_log.sh [output_file]

INTERVAL_SECONDS=1
INTERVAL_NS=$((INTERVAL_SECONDS * 1000000000))

timestamp="$(date +%Y%m%d_%H%M%S)"
log_file="${1:-top100_${timestamp}.log}"

echo "=== Log start: $(date '+%F %T') ===" >> "$log_file"
trap 'echo "=== Log complete: $(date "+%F %T") ===" >> "$log_file"; exit 0' INT

while true; do
  loop_start_ns="$(date +%s%N)"
  echo "=== Time: $(date '+%F %T') ===" >> "$log_file"
  top -b -n 1 >> "$log_file"
  echo "" >> "$log_file"

  loop_end_ns="$(date +%s%N)"
  elapsed_ns=$((loop_end_ns - loop_start_ns))
  if (( elapsed_ns < INTERVAL_NS )); then
    remaining_ns=$((INTERVAL_NS - elapsed_ns))
    sleep "$(printf '%d.%09d' $((remaining_ns / 1000000000)) \
      $((remaining_ns % 1000000000)))"
  fi
done
