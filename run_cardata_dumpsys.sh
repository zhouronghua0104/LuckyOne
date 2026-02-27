#!/system/bin/sh

# 每秒执行一次 dumpsys，按 Ctrl+C 退出
on_interrupt() {
  echo ""
  echo "已停止执行。"
  exit 0
}

trap on_interrupt INT TERM

while true; do
  echo "[$(date '+%Y-%m-%d %H:%M:%S')]"
  dumpsys activity service com.svw.modelservice/.ModelService cardata all
  echo ""
  sleep 1
done
