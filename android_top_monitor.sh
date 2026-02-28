#!/system/bin/sh

# Android process monitor:
# 1) collect CPU/MEM every second for 3 minutes (180 samples)
# 2) use top command
# 3) save output to process_[pid]_[timestamp].log by default
# 4) resolve pid from package name (default: com.crescent.myjnidemo)

DEFAULT_PACKAGE="com.crescent.myjnidemo"
SAMPLE_COUNT=180
SAMPLE_INTERVAL=1

usage() {
  echo "用法: $0 [包名] [输出文件]"
  echo "示例: $0 com.crescent.myjnidemo process_custom.log"
}

get_pid_by_package() {
  pkg="$1"
  pid=""

  # Preferred on Android
  if command -v pidof >/dev/null 2>&1; then
    pid="$(pidof "$pkg" 2>/dev/null | awk '{print $1}')"
    if [ -n "$pid" ]; then
      echo "$pid"
      return 0
    fi
  fi

  # Fallback for environments without pidof or when pidof has no result
  pid="$(ps -A 2>/dev/null | awk -v pkg="$pkg" '
    NR==1 {
      pid_col=0
      name_col=0
      for (i=1; i<=NF; i++) {
        if ($i=="PID") pid_col=i
        if ($i=="NAME" || $i=="COMMAND" || $i=="CMDLINE") name_col=i
      }
      next
    }
    {
      p = (pid_col>0 ? $pid_col : $2)
      n = (name_col>0 ? $name_col : $NF)
      if (n==pkg) { print p; exit }
    }
  ')"

  if [ -n "$pid" ]; then
    echo "$pid"
    return 0
  fi

  # Last fallback: match subprocess like pkg:remote
  pid="$(ps -A 2>/dev/null | awk -v pkg="$pkg" '
    NR==1 {
      pid_col=0
      name_col=0
      for (i=1; i<=NF; i++) {
        if ($i=="PID") pid_col=i
        if ($i=="NAME" || $i=="COMMAND" || $i=="CMDLINE") name_col=i
      }
      next
    }
    {
      p = (pid_col>0 ? $pid_col : $2)
      n = (name_col>0 ? $name_col : $NF)
      if (n ~ ("^" pkg ":")) { print p; exit }
    }
  ')"

  if [ -n "$pid" ]; then
    echo "$pid"
    return 0
  fi

  return 1
}

if [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
  usage
  exit 0
fi

PACKAGE_NAME="${1:-$DEFAULT_PACKAGE}"
PID="$(get_pid_by_package "$PACKAGE_NAME")"

if [ -z "$PID" ]; then
  echo "错误: 未找到包名 [$PACKAGE_NAME] 对应的进程 PID" >&2
  exit 1
fi

TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
LOG_FILE="${2:-process_${PID}_${TIMESTAMP}.log}"

{
  echo "===== Android 进程监控开始 ====="
  echo "包名: $PACKAGE_NAME"
  echo "PID: $PID"
  echo "开始时间: $(date '+%Y-%m-%d %H:%M:%S')"
  echo "采样间隔: ${SAMPLE_INTERVAL}s"
  echo "采样次数: ${SAMPLE_COUNT}"
  echo
} > "$LOG_FILE"

# Try to enforce 1s interval on implementations that support -d.
# If -d is not supported, fallback to the command requested by spec.
if top -b -d "$SAMPLE_INTERVAL" -n "$SAMPLE_COUNT" -p "$PID" >> "$LOG_FILE" 2>&1; then
  true
else
  {
    echo
    echo "[提示] 当前 top 可能不支持 -d 参数，回退为: top -b -n $SAMPLE_COUNT -p $PID"
    echo
  } >> "$LOG_FILE"
  top -b -n "$SAMPLE_COUNT" -p "$PID" >> "$LOG_FILE" 2>&1
fi

{
  echo
  echo "结束时间: $(date '+%Y-%m-%d %H:%M:%S')"
  echo "===== Android 进程监控结束 ====="
} >> "$LOG_FILE"

echo "监控完成，日志文件: $LOG_FILE"
