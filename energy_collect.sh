#!/system/bin/sh

# Android car battery energy collector
# Usage:
#   sh energy_collect.sh -i 1
#   sh energy_collect.sh -i 10 -n 60
#   sh energy_collect.sh -i 5 -o /data/local/tmp

INTERVAL=1
COUNT=0
OUT_DIR="."

usage() {
  echo "用法: sh energy_collect.sh [-i 间隔秒] [-n 采集次数] [-o 输出目录]"
  echo "  -i 采样间隔(秒)，默认 1"
  echo "  -n 采集次数，默认 0(无限循环，按 Ctrl+C 手动结束)"
  echo "  -o 日志输出目录，默认当前目录"
}

is_number() {
  case "$1" in
    ''|*[!0-9]*)
      return 1
      ;;
    *)
      return 0
      ;;
  esac
}

while getopts "i:n:o:h" opt; do
  case "$opt" in
    i)
      INTERVAL="$OPTARG"
      ;;
    n)
      COUNT="$OPTARG"
      ;;
    o)
      OUT_DIR="$OPTARG"
      ;;
    h)
      usage
      exit 0
      ;;
    *)
      usage
      exit 1
      ;;
  esac
done

if ! is_number "$INTERVAL" || [ "$INTERVAL" -le 0 ]; then
  echo "错误: -i 必须是大于 0 的整数。"
  exit 1
fi

if ! is_number "$COUNT" || [ "$COUNT" -lt 0 ]; then
  echo "错误: -n 必须是大于等于 0 的整数。"
  exit 1
fi

if [ ! -d "$OUT_DIR" ]; then
  echo "错误: 输出目录不存在: $OUT_DIR"
  exit 1
fi

TIMESTAMP="$(date '+%Y%m%d_%H%M%S')"
LOG_FILE="${OUT_DIR%/}/energy_${TIMESTAMP}.log"
SERVICE_CMD="dumpsys activity service com.svw.modelservice/.ModelService cardata battery"

log_line() {
  printf '%s\n' "$1" | tee -a "$LOG_FILE"
}

: > "$LOG_FILE"
log_line "# energy collect start: $(date '+%F %T')"
log_line "# interval=${INTERVAL}s, count=${COUNT}"
log_line "# command: ${SERVICE_CMD}"
log_line "# format: time|battery_percent|remaining_range|battery_voltage"
log_line ""

extract_field() {
  # $1: service output content
  # $2: field key, e.g. "电池百分比"
  echo "$1" | awk -v k="$2" '
    index($0, k) > 0 {
      line = $0
      sub(/^[^:]*:[[:space:]]*/, "", line)
      print line
      exit
    }
  '
}

STOP_REASON="未设置"

finish() {
  log_line "# energy collect stop: $(date '+%F %T')"
  log_line "# stop_reason: ${STOP_REASON}"
  echo "采集结束(${STOP_REASON})，日志文件: $LOG_FILE"
}

on_ctrl_c() {
  STOP_REASON="Ctrl+C"
  finish
  exit 130
}

trap on_ctrl_c INT

i=0
while :; do
  if [ "$COUNT" -gt 0 ] && [ "$i" -ge "$COUNT" ]; then
    STOP_REASON="达到N次采集(${COUNT})"
    break
  fi

  DUMP="$(dumpsys activity service com.svw.modelservice/.ModelService cardata battery 2>&1)"
  NOW="$(date '+%F %T')"

  BATTERY_PERCENT="$(extract_field "$DUMP" "电池百分比")"
  REMAINING_RANGE="$(extract_field "$DUMP" "剩余续航里程")"
  BATTERY_VOLTAGE="$(extract_field "$DUMP" "电池电压")"

  [ -z "$BATTERY_PERCENT" ] && BATTERY_PERCENT="NA"
  [ -z "$REMAINING_RANGE" ] && REMAINING_RANGE="NA"
  [ -z "$BATTERY_VOLTAGE" ] && BATTERY_VOLTAGE="NA"

  log_line "${NOW}|${BATTERY_PERCENT}|${REMAINING_RANGE}|${BATTERY_VOLTAGE}"
  log_line "----- raw begin ${NOW} -----"
  printf '%s\n' "$DUMP" | tee -a "$LOG_FILE"
  log_line "----- raw end ${NOW} -----"
  log_line ""

  i=$((i + 1))
  sleep "$INTERVAL"
done

if [ "$STOP_REASON" = "未设置" ]; then
  STOP_REASON="脚本正常结束"
fi
finish
