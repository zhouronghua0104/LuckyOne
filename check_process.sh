#!/system/bin/sh
# Android shell script to check process by package/keyword.

set -u

print_usage() {
  echo "用法: $0 <包名或关键字> [输出文件路径]"
  echo "示例: $0 com.svw.appstore /data/local/tmp/process_check.txt"
}

if [ $# -lt 1 ]; then
  print_usage
  exit 1
fi

KEYWORD="$1"
OUT_FILE="${2:-/data/local/tmp/process_check.txt}"
NOW="$(date '+%Y-%m-%d %H:%M:%S')"

OUT_DIR="$(dirname "$OUT_FILE")"
if [ ! -d "$OUT_DIR" ]; then
  mkdir -p "$OUT_DIR"
fi

# Use ps -ef as shown in the example, and filter out the grep process itself.
MATCH_LINES="$(ps -ef | grep -F -e "$KEYWORD" | grep -v -e "grep")"

{
  echo "[$NOW] 关键字: $KEYWORD"
  if [ -n "$MATCH_LINES" ]; then
    echo "$MATCH_LINES"
    echo "检测到 $KEYWORD 还存在"
  else
    echo "$KEYWORD 不存在"
  fi
  echo "----------------------------------------"
} >> "$OUT_FILE"

echo "结果已写入: $OUT_FILE"
