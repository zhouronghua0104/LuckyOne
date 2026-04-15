#!/usr/bin/env bash
# EDC + AIBOX 环境部署入口：在检测到设备未连接时自动执行 adb connect。
set -euo pipefail

EDC_IP="${EDC_IP:-172.16.6.50}"
EDC_PORT="${EDC_PORT:-5555}"
EDC_ADDR="${EDC_IP}:${EDC_PORT}"
ADB_CONNECT_RETRIES="${ADB_CONNECT_RETRIES:-3}"
ADB_CONNECT_SLEEP_SEC="${ADB_CONNECT_SLEEP_SEC:-2}"

edc_state() {
  # 输出该地址在 adb devices 中的第二列状态：device / offline / unauthorized / 空（未列出）
  adb devices 2>/dev/null | awk -v a="$EDC_ADDR" '$1==a {print $2; exit}'
}

edc_is_ready() {
  [[ "$(edc_state)" == "device" ]]
}

try_adb_connect() {
  local attempt out
  for ((attempt = 1; attempt <= ADB_CONNECT_RETRIES; attempt++)); do
    echo "  --> 尝试 adb 连接 (${attempt}/${ADB_CONNECT_RETRIES}): adb connect ${EDC_ADDR}"
    set +e
    out="$(adb connect "${EDC_ADDR}" 2>&1)"
    code=$?
    set -e
    echo "      ${out}"
    if edc_is_ready; then
      return 0
    fi
    sleep "${ADB_CONNECT_SLEEP_SEC}"
  done
  return 1
}

echo "=============================================="
echo "  EDC + AIBOX 环境部署"
echo "=============================================="
echo ""

adb start-server >/dev/null 2>&1 || true

echo "  --> 当前 adb 设备列表："
adb devices
echo ""

if edc_is_ready; then
  echo "  [√] 已检测到 EDC（${EDC_ADDR}），状态为 device。"
else
  state="$(edc_state)"
  if [[ -n "${state}" ]]; then
    echo "  [!] EDC（${EDC_ADDR}）当前状态为「${state}」，将尝试重新连接…"
  else
    echo "  [x] 未检测到 EDC（${EDC_IP}）。正在自动执行: adb connect ${EDC_ADDR}"
  fi

  if ! try_adb_connect; then
    echo ""
    echo "  [x] 自动连接失败。请检查："
    echo "      - 设备已开启 USB/无线调试，且端口 ${EDC_PORT} 可达"
    echo "      - 本机与 ${EDC_IP} 网络互通"
    echo "      - 可手动执行: adb connect ${EDC_ADDR}"
    exit 1
  fi

  echo ""
  echo "  --> 连接后的 adb 设备列表："
  adb devices
  echo ""
  echo "  [√] 已自动连接 EDC（${EDC_ADDR}）。"
fi

echo ""
echo "  （在此脚本末尾追加你的部署步骤，或 source 本脚本后继续使用 adb。）"
