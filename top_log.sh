#!/usr/bin/env bash
set -euo pipefail

# Collect top snapshots every second and write to a log file.
# Usage: ./top_log.sh [output_file]

SAMPLES=100
INTERVAL=1

timestamp="$(date +%Y%m%d_%H%M%S)"
log_file="${1:-top100_${timestamp}.log}"

echo "=== Log start: $(date '+%F %T') ===" >> "$log_file"
for ((i=1; i<=SAMPLES; i++)); do
  echo "=== Time: $(date '+%F %T') ===" >> "$log_file"
  top -b -n 1 >> "$log_file"
  if (( i < SAMPLES )); then
    sleep "$INTERVAL"
  fi
done
echo "=== Log complete: $(date '+%F %T') ===" >> "$log_file"
