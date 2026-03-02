#!/system/bin/sh

# 每秒执行一次 dumpsys，按 Ctrl+C 退出
on_interrupt() {
  echo ""
  echo "已停止执行。"
  exit 0
}

trap on_interrupt INT TERM

SIGNAL_COUNT="$1"

if [ -z "$SIGNAL_COUNT" ]; then
  echo "用法: $0 <信号个数>"
  echo "示例: $0 5"
  exit 1
fi

case "$SIGNAL_COUNT" in
  ''|*[!0-9]*)
    echo "错误: 信号个数必须为正整数。"
    exit 1
    ;;
  0)
    echo "错误: 信号个数必须大于 0。"
    exit 1
    ;;
esac

while true; do
  echo "[$(date '+%Y-%m-%d %H:%M:%S')]"
  dumpsys activity service com.svw.modelservice/.ModelService cardata all "$SIGNAL_COUNT"
  echo ""
  sleep 1
done
