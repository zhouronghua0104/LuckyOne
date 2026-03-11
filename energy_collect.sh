#!/system/bin/sh

# Android battery energy collector
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
  echo "  -n 采集次数，默认 0(仅支持 Ctrl+C 手动结束)"
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

echo "# energy collect start: $(date '+%F %T')" > "$LOG_FILE"
echo "# interval=${INTERVAL}s, count=${COUNT}" >> "$LOG_FILE"
echo "# format: time|AC|USB|WIRELESS|max_current|max_voltage|charge_counter|status|health|present|level|scale|voltage|temperature|technology" >> "$LOG_FILE"

extract_field() {
  # $1: dumpsys battery content
  # $2: field key with leading spaces, e.g. "  level"
  echo "$1" | awk -F': ' -v k="$2" '$1==k{print $2; exit}'
}

STOP_REASON="未设置"

finish() {
  echo "# energy collect stop: $(date '+%F %T')" >> "$LOG_FILE"
  echo "# stop_reason: ${STOP_REASON}" >> "$LOG_FILE"
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

  DUMP="$(dumpsys battery)"
  NOW="$(date '+%F %T')"

  AC="$(extract_field "$DUMP" "  AC powered")"
  USB="$(extract_field "$DUMP" "  USB powered")"
  WIRELESS="$(extract_field "$DUMP" "  Wireless powered")"
  MAX_CURRENT="$(extract_field "$DUMP" "  Max charging current")"
  MAX_VOLTAGE="$(extract_field "$DUMP" "  Max charging voltage")"
  CHARGE_COUNTER="$(extract_field "$DUMP" "  Charge counter")"
  STATUS="$(extract_field "$DUMP" "  status")"
  HEALTH="$(extract_field "$DUMP" "  health")"
  PRESENT="$(extract_field "$DUMP" "  present")"
  LEVEL="$(extract_field "$DUMP" "  level")"
  SCALE="$(extract_field "$DUMP" "  scale")"
  VOLTAGE="$(extract_field "$DUMP" "  voltage")"
  TEMPERATURE="$(extract_field "$DUMP" "  temperature")"
  TECHNOLOGY="$(extract_field "$DUMP" "  technology")"

  echo "${NOW}|${AC}|${USB}|${WIRELESS}|${MAX_CURRENT}|${MAX_VOLTAGE}|${CHARGE_COUNTER}|${STATUS}|${HEALTH}|${PRESENT}|${LEVEL}|${SCALE}|${VOLTAGE}|${TEMPERATURE}|${TECHNOLOGY}" >> "$LOG_FILE"

  i=$((i + 1))
  sleep "$INTERVAL"
done

if [ "$STOP_REASON" = "未设置" ]; then
  STOP_REASON="脚本正常结束"
fi
finish
