#!/usr/bin/env bash
set -euo pipefail

# Defaults
APK_PATH=""
MODEL_DIR_DEFAULT="./ark_000007_20250811_8850"
LIBS_DIR_DEFAULT="./BibaoLibs/lib"
MODEL_DIRS=("$MODEL_DIR_DEFAULT")
LIBS_DIR="$LIBS_DIR_DEFAULT"
ADB_SERIAL=""

usage() {
  cat <<EOF
Usage: $(basename "$0") --apk <path_to_apk> [--model-dir <local_model_dir> ...] [--libs-dir <local_lib_dir>] [--serial <device_serial>]

Required:
  --apk         Path to local APK file to push to device (/vendor/app/qcguiagent/)

Optional:
  --model-dir   Local model directory to push (repeatable). Default includes: ${MODEL_DIR_DEFAULT}
  --libs-dir    Local runtime libs directory to push to device vendor path (default: ${LIBS_DIR_DEFAULT})
  --serial,-s   Target device serial if multiple devices are connected
  -h, --help    Show this help

Notes:
- Expects ADB available in PATH and a connected device with root access.
- Will adb root + remount, install model(s) if missing, install libs, uninstall old app, push new APK, reboot, wait, then disable SELinux.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk)
      APK_PATH="${2:-}"
      shift 2
      ;;
    --model-dir)
      MODEL_DIRS+=("${2:-}")
      shift 2
      ;;
    --libs-dir)
      LIBS_DIR="${2:-}"
      shift 2
      ;;
    --serial|-s)
      ADB_SERIAL="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "[ERR] Unknown argument: $1" >&2
      usage
      exit 2
      ;;
  esac
done

if [[ -z "$APK_PATH" ]]; then
  echo "[ERR] --apk is required" >&2
  usage
  exit 2
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "[ERR] adb not found in PATH" >&2
  exit 1
fi

if [[ ! -f "$APK_PATH" ]]; then
  echo "[ERR] APK not found: $APK_PATH" >&2
  exit 1
fi

if [[ ! -d "$LIBS_DIR" ]]; then
  echo "[ERR] libs dir not found: $LIBS_DIR" >&2
  exit 1
fi

ADB_ARGS=()
if [[ -n "$ADB_SERIAL" ]]; then
  ADB_ARGS=(-s "$ADB_SERIAL")
fi

adb_cmd() {
  adb "${ADB_ARGS[@]}" "$@"
}

log() {
  printf "[%-5s] %s\n" "$1" "$2"
}

wait_for_device() {
  log INFO "Waiting for device..."
  adb_cmd wait-for-device
}

wait_for_boot_completed() {
  local retries=180
  log INFO "Waiting for Android boot completion (timeout ~${retries}*2s)..."
  while (( retries > 0 )); do
    local bc1 bc2
    bc1=$(adb_cmd shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') || true
    bc2=$(adb_cmd shell getprop dev.bootcomplete 2>/dev/null | tr -d '\r') || true
    if [[ "$bc1" == "1" || "$bc2" == "1" ]]; then
      log INFO "Boot completed"
      return 0
    fi
    sleep 2
    ((retries--))
  done
  echo "[ERR] Timeout waiting for boot to complete" >&2
  return 1
}

ensure_root_and_remount() {
  log INFO "adb root"
  adb_cmd root || true
  sleep 1
  wait_for_device
  log INFO "adb remount"
  adb_cmd remount || true
}

install_model_if_missing() {
  log INFO "Ensuring model directories on device"
  adb_cmd shell "mkdir -p /data/local/qcguiagent"
  for local_dir in "${MODEL_DIRS[@]}"; do
    local base_name remote_dir
    base_name="$(basename "$local_dir")"
    remote_dir="/data/local/qcguiagent/${base_name}"

    log INFO "Checking model at ${remote_dir}"
    if adb_cmd shell "[ -e '${remote_dir}' ]"; then
      log INFO "Model present: ${remote_dir}; skipping"
      continue
    fi

    if [[ ! -e "$local_dir" ]]; then
      echo "[ERR] Local model not found: $local_dir (required because device is missing this model)" >&2
      exit 1
    fi

    log INFO "Pushing model from '${local_dir}' to '/data/local/qcguiagent'"
    adb_cmd push "$local_dir" "/data/local/qcguiagent"

    log INFO "Setting owner system:system on ${remote_dir}"
    adb_cmd shell "chown -R system:system '${remote_dir}'" || true

    log INFO "Setting permissions 777 on ${remote_dir}"
    adb_cmd shell "chmod -R 777 '${remote_dir}'" || true
  done
}

install_runtime_libs() {
  log INFO "Creating /vendor/app/qcguiagent"
  adb_cmd shell "mkdir -p /vendor/app/qcguiagent"

  log INFO "Pushing runtime libs from '$LIBS_DIR' to '/vendor/app/qcguiagent'"
  adb_cmd push "$LIBS_DIR" /vendor/app/qcguiagent

  log INFO "chmod -R 777 /vendor/app/qcguiagent/lib"
  adb_cmd shell "chmod -R 777 /vendor/app/qcguiagent/lib" || true
}

install_app() {
  log INFO "Uninstalling old app: com.modelbest.qcguiagent (ignore errors if not installed)"
  adb_cmd uninstall com.modelbest.qcguiagent || true

  log INFO "Removing old APKs from /vendor/app/qcguiagent"
  adb_cmd shell "rm -Rvf /vendor/app/qcguiagent/*.apk || true" || true

  log INFO "Pushing APK to /vendor/app/qcguiagent/"
  adb_cmd push "$APK_PATH" /vendor/app/qcguiagent/
}

post_reboot_disable_selinux() {
  log INFO "Re-root after reboot"
  adb_cmd root || true
  sleep 1
  wait_for_device

  log INFO "Disabling SELinux (setenforce 0)"
  adb_cmd shell setenforce 0 || true

  local mode
  mode=$(adb_cmd shell getenforce 2>/dev/null | tr -d '\r' || true)
  log INFO "SELinux mode: ${mode:-unknown}"
}

main() {
  wait_for_device
  ensure_root_and_remount
  install_model_if_missing
  install_runtime_libs
  install_app

  log INFO "Rebooting device"
  adb_cmd reboot || true
  wait_for_device
  wait_for_boot_completed || true

  log INFO "Current devices list"
  adb devices | cat || true

  post_reboot_disable_selinux

  log INFO "All done"
}

main "$@"