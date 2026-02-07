#!/system/bin/sh
# Android shell script to check process by package/keyword.

set -u

print_usage() {
  echo "用法: $0 [输出文件路径]"
  echo "示例: $0 /data/local/tmp/process_check.txt"
}

if [ $# -gt 1 ]; then
  print_usage
  exit 1
fi

# 在此处配置要检测的包名/关键字（空格分隔）
KEYWORDS="com.svw.appstore com.svw.appstore com.svw.dms com.svw.payment"

OUT_FILE="${1:-/data/local/tmp/process_check.txt}"

OUT_DIR="$(dirname "$OUT_FILE")"
if [ ! -d "$OUT_DIR" ]; then
  mkdir -p "$OUT_DIR"
fi

for KEYWORD in $KEYWORDS; do
  NOW="$(date '+%Y-%m-%d %H:%M:%S')"
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
done

echo "结果已写入: $OUT_FILE"
