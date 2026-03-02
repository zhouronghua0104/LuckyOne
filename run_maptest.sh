#!/usr/bin/env bash
set -euo pipefail

MODEL_SERVICE="com.svw.modelservice/.ModelService"
SLEEP_SECONDS=3

usage() {
  cat <<'EOF'
用法:
  ./run_maptest.sh /path/to/xxx.json

说明:
  读取 JSON 测试集中的每个测试项 intent，逐条执行:
    adb shell dumpsys activity service com.svw.modelservice/.ModelService inference maptest [intent]
EOF
}

extract_intents_null_delimited() {
  local json_file="$1"

  if command -v jq >/dev/null 2>&1; then
    jq -j '
      if type != "array" then
        error("输入 JSON 根节点必须是数组")
      else
        .
      end
      | .[]
      | if (type == "object" and has("intent") and (.intent | type == "string")) then
          .intent, "\u0000"
        else
          error("每个测试项必须包含字符串类型的 intent 字段")
        end
    ' "$json_file"
    return
  fi

  if command -v python3 >/dev/null 2>&1; then
    python3 - "$json_file" <<'PY'
import json
import sys

path = sys.argv[1]
with open(path, "r", encoding="utf-8") as f:
    data = json.load(f)

if not isinstance(data, list):
    raise SystemExit("输入 JSON 根节点必须是数组")

for idx, item in enumerate(data, start=1):
    if not isinstance(item, dict):
        raise SystemExit(f"第 {idx} 项不是对象")
    intent = item.get("intent")
    if not isinstance(intent, str):
        raise SystemExit(f"第 {idx} 项缺少字符串类型 intent")
    sys.stdout.write(intent)
    sys.stdout.write("\0")
PY
    return
  fi

  echo "错误: 未检测到 jq 或 python3，无法解析 JSON 文件。" >&2
  echo "请安装 jq 或 python3 后重试。" >&2
  return 1
}

run_one_inference() {
  local intent="$1"

  adb shell sh -c \
    'dumpsys activity service "$1" inference maptest "$2"' \
    sh "$MODEL_SERVICE" "$intent"
}

main() {
  if [[ $# -ne 1 ]]; then
    usage
    exit 1
  fi

  local json_file="$1"
  if [[ ! -f "$json_file" ]]; then
    echo "错误: 文件不存在: $json_file" >&2
    exit 1
  fi

  if ! command -v adb >/dev/null 2>&1; then
    echo "错误: 未检测到 adb，请先安装 Android platform-tools。" >&2
    exit 1
  fi

  local -a intents=()
  mapfile -d '' -t intents < <(extract_intents_null_delimited "$json_file")

  local total="${#intents[@]}"
  local success_count=0
  local failed_count=0

  if (( total == 0 )); then
    echo "任务执行完成: 测试项总数 0。"
    exit 0
  fi

  echo "开始执行 maptest，测试项总数: $total"
  local i=0
  for intent in "${intents[@]}"; do
    i=$((i + 1))
    echo "[$i/$total] intent: $intent"

    if run_one_inference "$intent"; then
      success_count=$((success_count + 1))
    else
      failed_count=$((failed_count + 1))
      echo "[$i/$total] 执行失败，继续下一条。" >&2
    fi

    if (( i < total )); then
      sleep "$SLEEP_SECONDS"
    fi
  done

  echo "任务执行完成: 共执行 $total 个测试项，成功 $success_count 个，失败 $failed_count 个。"
}

main "$@"
