#!/bin/sh
# 使用方法：
# 1. 杀掉进程并且防止自动启动
# kill_targets.sh disable --user 0
# 2. 恢复进程自动启动
# kill_targets.sh enable --user 0

MODE="${1:-disable}"

TARGETS="
com.svw.mediacenter.rear
com.svw.mediacenter.rear:xmplayer
com.svw.avatar
com.svw.avatarland
com.zone.service.speech
com.zone.service.speech.dialogue
com.zone.hmi.settings
com.unity3d.renderservice
com.svw.omsservice
"

get_pkg() { echo "$1" | awk -F: '{print $1}'; }

ps_list() { ps -A 2>/dev/null; }

get_pids() {
  name="$1"
  ps_list \
    | grep -F "$name" \
    | grep -v "grep" \
    | awk -v n="$name" '
        $NF==n {
          for (i=1; i<=NF; i++) if ($i ~ /^[0-9]+$/) { print $i; break }
        }
      ' \
    | awk '$1 ~ /^[0-9]+$/' \
    | sort -u
}

force_stop_pkg() {
  pkg="$1"
  command -v am >/dev/null 2>&1 || return 0
  am force-stop "$pkg" 2>/dev/null || true
}

disable_pkg() {
  pkg="$1"
  if command -v cmd >/dev/null 2>&1; then
    cmd package disable-user --user 0 "$pkg" 2>/dev/null && return 0
    cmd package disable "$pkg" 2>/dev/null && return 0
  fi
  command -v pm >/dev/null 2>&1 || return 1
  pm disable-user --user 0 "$pkg" 2>/dev/null && return 0
  pm disable "$pkg" 2>/dev/null && return 0
  return 1
}

enable_pkg() {
  pkg="$1"
  if command -v cmd >/dev/null 2>&1; then
    cmd package enable "$pkg" 2>/dev/null && return 0
  fi
  command -v pm >/dev/null 2>&1 || return 1
  pm enable "$pkg" 2>/dev/null && return 0
  return 1
}

pkg_list_unique() {
  echo "$TARGETS" | tr -d '\r' | awk 'NF{print}' | awk -F: '{print $1}' | sort -u
}

kill_one() {
  name="$1"
  pkg="$(get_pkg "$name")"

  force_stop_pkg "$pkg"

  pids="$(get_pids "$name")"
  [ -z "$pids" ] && echo "[SKIP] process not found: $name" && return 0

  echo "[TERM] $name -> $pids"
  for pid in $pids; do kill -15 "$pid" 2>/dev/null; done

  alive=""
  for t in 1 2 3 4 5; do
    alive=""
    for pid in $pids; do kill -0 "$pid" 2>/dev/null && alive="$alive $pid"; done
    [ -z "$alive" ] && return 0
    sleep 1
  done

  echo "[KILL] $name ->$alive"
  for pid in $alive; do kill -9 "$pid" 2>/dev/null; done
}

case "$MODE" in
  enable)
    pkg_list_unique | while IFS= read -r pkg; do
      [ -z "$pkg" ] && continue
      if enable_pkg "$pkg"; then
        echo "[ENABLED] $pkg"
      else
        echo "[WARN] enable failed (need permission?): $pkg"
      fi
    done
    if command -v am >/dev/null 2>&1; then
      am start -n com.zone.hmi.settings/.ui.MainFragmentActivity 2>/dev/null || true
    fi
    ;;
  disable|"")
    # 先按进程名顺序 kill
    echo "$TARGETS" | tr -d '\r' | while IFS= read -r name; do
      [ -z "$name" ] && continue
      kill_one "$name"
    done

    # 再按包名去重 disable（防止重启）
    pkg_list_unique | while IFS= read -r pkg; do
      [ -z "$pkg" ] && continue
      if disable_pkg "$pkg"; then
        echo "[DISABLED] $pkg"
      else
        echo "[WARN] disable failed (need root/permission?): $pkg"
      fi
    done
    ;;
  *)
    echo "Usage: sh $0 [disable|enable]"
    exit 2
    ;;
esac
