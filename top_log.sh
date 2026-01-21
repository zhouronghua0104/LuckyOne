#!/usr/bin/env bash
set -euo pipefail

# Collect top snapshots every second and write to a log file.
# Runs until Ctrl+C is pressed.
# Usage: ./top_log.sh [output_file]

INTERVAL=1

timestamp="$(date +%Y%m%d_%H%M%S)"
log_file="${1:-top100_${timestamp}.log}"

echo "=== Log start: $(date '+%F %T') ===" >> "$log_file"
trap 'echo "=== Log complete: $(date "+%F %T") ===" >> "$log_file"; exit 0' INT

while true; do
  echo "=== Time: $(date '+%F %T') ===" >> "$log_file"
  top -b -n 1 >> "$log_file"
  echo "" >> "$log_file"
  sleep "$INTERVAL"
done
