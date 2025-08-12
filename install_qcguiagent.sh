#!/usr/bin/env bash
set -euo pipefail

# install_qcguiagent.sh
# macOS-compatible Bash script to provision device models, runtime libs, and app via ADB.
#
# Usage:
#   ./install_qcguiagent.sh [--apk /path/to/app.apk] [--serial SERIAL] \
#       [--model7 ./ark_000007_20250811_8850] [--model8 ./ark_000008_20250725_8850] \
#       [--libs ./BibaoLibs/lib]
#
# Steps implemented:
# 1) adb root && adb remount
# 2) Check and install model ark_000007_20250811_8850 if missing
# 4) Check and install model ark_000008_20250725_8850 if missing
# 5) Install runtime libs BibaoLibs/lib -> /vendor/app/qcguiagent/lib
# 6) Uninstall old app, remove old apk(s), push new apk
# 7) Reboot
# 8) Wait for reboot completion
# 9) adb root; disable SELinux (setenforce 0)
#
# Additionally:
# - If local model or libs directories are missing, they will be downloaded from predefined URLs and unzipped.
# - If APK is not provided or missing locally, it will be downloaded and unzipped automatically.

SCRIPT_NAME=$(basename "$0")

EXPECTED_MODEL7_NAME="ark_000007_20250811_8850"
EXPECTED_MODEL8_NAME="ark_000008_20250725_8850"
DEVICE_MODEL_DIR="/data/local/qcguiagent"
DEVICE_VENDOR_DIR="/vendor/app/qcguiagent"
PACKAGE_NAME="com.modelbest.qcguiagent"

# Download URLs
MODEL7_ZIP_URL="https://minicpm.oss-cn-beijing.aliyuncs.com/qualcomm/ark_000007_20250811_8850.zip"
MODEL8_ZIP_URL="https://minicpm.oss-cn-beijing.aliyuncs.com/qualcomm/ark_000008_20250725_8850.zip"
LIBS_ZIP_URL="https://minicpm.oss-cn-beijing.aliyuncs.com/qualcomm/opencl/lib.zip"
APK_ZIP_URL="https://minicpm.oss-cn-beijing.aliyuncs.com/qualcomm/opencl/QcBibao-release-latest.apk.zip"
DEFAULT_APK_NAME="QcBibao-release-latest.apk"

# Defaults (can be overridden by CLI)
LOCAL_MODEL7="./${EXPECTED_MODEL7_NAME}"
LOCAL_MODEL8="./${EXPECTED_MODEL8_NAME}"
LOCAL_LIBS_DIR="./BibaoLibs/lib"
APK_PATH=""
SERIAL=""

print_usage() {
  cat <<EOF
${SCRIPT_NAME} - Provision device models, libs and app via ADB

Options:
  --apk PATH                 Path to the APK file. If omitted or missing, the script will download it.
  --serial SERIAL            Target device serial (when multiple devices connected)
  --model7 PATH              Local path to ${EXPECTED_MODEL7_NAME} (default: ${LOCAL_MODEL7})
  --model8 PATH              Local path to ${EXPECTED_MODEL8_NAME} (default: ${LOCAL_MODEL8})
  --libs PATH                Local path to BibaoLibs/lib (default: ${LOCAL_LIBS_DIR})
  -h, --help                 Show this help and exit

Notes:
  - Missing model or libs directories will be downloaded and unzipped automatically.
  - Missing APK will be downloaded from ${APK_ZIP_URL} and extracted as ${DEFAULT_APK_NAME} in the current directory.

Examples:
  ${SCRIPT_NAME} --apk ./qcguiagent-release.apk
  ${SCRIPT_NAME} --serial ABC123
EOF
}

fail() { echo "[ERROR] $*" >&2; exit 1; }
log()  { echo "[INFO]  $*"; }
warn() { echo "[WARN]  $*"; }

# Parse arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk) APK_PATH=${2:-}; shift 2;;
    --serial) SERIAL=${2:-}; shift 2;;
    --model7) LOCAL_MODEL7=${2:-}; shift 2;;
    --model8) LOCAL_MODEL8=${2:-}; shift 2;;
    --libs) LOCAL_LIBS_DIR=${2:-}; shift 2;;
    -h|--help) print_usage; exit 0;;
    *) fail "Unknown argument: $1";;
  esac
done

# Validate required tools
command -v adb >/dev/null 2>&1 || fail "adb not found in PATH. Please install Android platform-tools."

# Helper to run adb with optional serial
adb_run() {
  if [[ -n "${SERIAL}" ]]; then
    adb -s "${SERIAL}" "$@"
  else
    adb "$@"
  fi
}

# Device-side existence check
device_path_exists() {
  local device_path="$1"
  if adb_run shell "test -e '${device_path}'" >/dev/null 2>&1; then
    return 0
  else
    return 1
  fi
}

ensure_root_and_remount() {
  log "Ensuring adb root..."
  if ! adb_run root >/dev/null 2>&1; then
    warn "adb root may not be supported on this build; continuing."
  fi
  sleep 1
  log "Remounting partitions read-write..."
  if ! adb_run remount | cat; then
    warn "adb remount failed; pushing to protected partitions may not work."
  fi
}

wait_for_device_and_boot() {
  log "Waiting for device to be online..."
  adb_run wait-for-device

  log "Waiting for Android boot completion..."
  local i
  for i in {1..180}; do
    local boot sys_boot dev_boot
    sys_boot=$(adb_run shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | tr -d '\n' || true)
    dev_boot=$(adb_run shell getprop dev.bootcomplete 2>/dev/null | tr -d '\r' | tr -d '\n' || true)
    if [[ "${sys_boot}" == "1" || "${dev_boot}" == "1" ]]; then
      break
    fi
    sleep 1
  done
  log "Device boot complete."
}

# ------------------------------------------------------------
# Download helpers and preflight for local assets
# ------------------------------------------------------------
require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

download_file() {
  local url="$1"
  local output="$2"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 --connect-timeout 15 -o "$output" "$url"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$output" "$url"
  else
    fail "Neither curl nor wget is available for downloads"
  fi
}

unzip_to_dir() {
  local zip_path="$1"
  local dest_dir="$2"
  require_cmd unzip
  mkdir -p "$dest_dir"
  unzip -o "$zip_path" -d "$dest_dir" >/dev/null
}

ensure_local_assets() {
  # Ensure model7 directory exists
  if [[ ! -d "${LOCAL_MODEL7}" ]]; then
    log "Local model missing: ${LOCAL_MODEL7}. Downloading..."
    local tmpzip
    tmpzip=$(mktemp -t model7.XXXXXX.zip)
    download_file "${MODEL7_ZIP_URL}" "${tmpzip}"
    unzip_to_dir "${tmpzip}" "$(dirname "${LOCAL_MODEL7}")"
    rm -f "${tmpzip}"
    [[ -d "${LOCAL_MODEL7}" ]] || fail "After download, directory still missing: ${LOCAL_MODEL7}"
  else
    log "Local model exists: ${LOCAL_MODEL7}"
  fi

  # Ensure model8 directory exists
  if [[ ! -d "${LOCAL_MODEL8}" ]]; then
    log "Local model missing: ${LOCAL_MODEL8}. Downloading..."
    local tmpzip
    tmpzip=$(mktemp -t model8.XXXXXX.zip)
    download_file "${MODEL8_ZIP_URL}" "${tmpzip}"
    unzip_to_dir "${tmpzip}" "$(dirname "${LOCAL_MODEL8}")"
    rm -f "${tmpzip}"
    [[ -d "${LOCAL_MODEL8}" ]] || fail "After download, directory still missing: ${LOCAL_MODEL8}"
  else
    log "Local model exists: ${LOCAL_MODEL8}"
  fi

  # Ensure libs directory exists under LOCAL_LIBS_DIR
  if [[ ! -d "${LOCAL_LIBS_DIR}" ]]; then
    log "Local libs missing: ${LOCAL_LIBS_DIR}. Downloading..."
    local tmpzip parent_dir
    tmpzip=$(mktemp -t libs.XXXXXX.zip)
    parent_dir=$(dirname "${LOCAL_LIBS_DIR}")
    mkdir -p "${parent_dir}"
    download_file "${LIBS_ZIP_URL}" "${tmpzip}"
    unzip_to_dir "${tmpzip}" "${parent_dir}"
    rm -f "${tmpzip}"
    [[ -d "${LOCAL_LIBS_DIR}" ]] || fail "After download, directory still missing: ${LOCAL_LIBS_DIR}"
  else
    log "Local libs exist: ${LOCAL_LIBS_DIR}"
  fi

  # Ensure APK exists (download if missing or not provided)
  if [[ -z "${APK_PATH}" || ! -f "${APK_PATH}" ]]; then
    log "APK missing or not provided. Downloading from ${APK_ZIP_URL}..."
    local tmpzip
    tmpzip=$(mktemp -t apk.XXXXXX.zip)
    download_file "${APK_ZIP_URL}" "${tmpzip}"
    unzip_to_dir "${tmpzip}" "."
    rm -f "${tmpzip}"
    # Set APK_PATH if still empty or not found
    if [[ -z "${APK_PATH}" ]]; then
      if [[ -f "./${DEFAULT_APK_NAME}" ]]; then
        APK_PATH="./${DEFAULT_APK_NAME}"
      else
        # Fallback: find any .apk recursively
        APK_PATH=$(find . -type f -name "*.apk" 2>/dev/null | head -n1 || true)
      fi
    fi
    [[ -n "${APK_PATH}" && -f "${APK_PATH}" ]] || fail "After download, APK not found. Expected ./${DEFAULT_APK_NAME}"
  else
    log "Local APK exists: ${APK_PATH}"
  fi
}

# ------------------------------------------------------------
# Push a local path (file or directory) to device parent dir, then rename to expected name if needed
# ------------------------------------------------------------
push_to_device_as_name() {
  local local_path="$1"       # e.g. ./ark_000007_...
  local device_parent="$2"    # e.g. /data/local/qcguiagent
  local expected_name="$3"    # e.g. ark_000007_...
  local local_name
  local_name=$(basename "${local_path}")

  [[ -e "${local_path}" ]] || fail "Local path does not exist: ${local_path}"

  log "Pushing '${local_path}' to '${device_parent}'..."
  adb_run push "${local_path}" "${device_parent}" | cat

  if [[ "${local_name}" != "${expected_name}" ]]; then
    log "Renaming on device: ${local_name} -> ${expected_name}"
    adb_run shell "mv -f '${device_parent}/${local_name}' '${device_parent}/${expected_name}'"
  fi
}

# Install a model if missing at the device path
install_model_if_missing() {
  local local_path="$1"
  local device_dir="$2"         # e.g. /data/local/qcguiagent
  local expected_name="$3"      # e.g. ark_000007_...
  local device_full_path="${device_dir}/${expected_name}"

  if device_path_exists "${device_full_path}"; then
    log "Model exists on device: ${device_full_path}"
    return 0
  fi

  log "Model missing; creating directory and installing: ${device_full_path}"
  adb_run shell "mkdir -p '${device_dir}'"
  push_to_device_as_name "${local_path}" "${device_dir}" "${expected_name}"

  log "Setting owner and permissions: ${device_full_path}"
  adb_run shell "chown -R system:system '${device_full_path}'"
  adb_run shell "chmod -R 777 '${device_full_path}'"
}

# Install runtime libs (always push to ensure latest), then chmod
install_runtime_libs() {
  local local_lib_dir="$1"   # e.g. ./BibaoLibs/lib
  [[ -d "${local_lib_dir}" ]] || fail "Local libs directory not found: ${local_lib_dir}"

  log "Creating vendor app directory: ${DEVICE_VENDOR_DIR}"
  adb_run shell "mkdir -p '${DEVICE_VENDOR_DIR}'"

  log "Pushing runtime libs to device: ${DEVICE_VENDOR_DIR}"
  adb_run push "${local_lib_dir}" "${DEVICE_VENDOR_DIR}" | cat

  log "Setting permissions for runtime libs"
  adb_run shell "chmod -R 777 '${DEVICE_VENDOR_DIR}/lib'" || warn "chmod on libs failed"
}

# Uninstall and push APK
install_app_apk() {
  local apk_path="$1"

  log "Uninstalling old app: ${PACKAGE_NAME} (ignore errors if not installed)"
  if ! adb_run uninstall "${PACKAGE_NAME}" | cat; then
    warn "Uninstall returned non-zero; continuing"
  fi

  log "Removing old APK files from ${DEVICE_VENDOR_DIR}"
  adb_run shell "mkdir -p '${DEVICE_VENDOR_DIR}' && rm -f '${DEVICE_VENDOR_DIR}'/*.apk" || true

  log "Pushing APK to device: ${apk_path} -> ${DEVICE_VENDOR_DIR}/"
  adb_run push "${apk_path}" "${DEVICE_VENDOR_DIR}/" | cat
}

main() {
  # Show connected devices if multiple; warn if ambiguous unless --serial provided
  local device_count
  device_count=$(adb devices | awk 'NR>1 && $2=="device" {count++} END {print count+0}')
  if [[ "${device_count}" -gt 1 && -z "${SERIAL}" ]]; then
    warn "Multiple devices detected. Consider using --serial to target a specific device. Proceeding with default.";
  fi

  log "Pre-download: Ensuring local models, libs and APK exist"
  ensure_local_assets

  log "Step 1: Acquire root and remount"
  ensure_root_and_remount

  log "Step 2/3: Ensure model ${EXPECTED_MODEL7_NAME} is installed"
  install_model_if_missing "${LOCAL_MODEL7}" "${DEVICE_MODEL_DIR}" "${EXPECTED_MODEL7_NAME}"

  log "Step 4: Ensure model ${EXPECTED_MODEL8_NAME} is installed"
  install_model_if_missing "${LOCAL_MODEL8}" "${DEVICE_MODEL_DIR}" "${EXPECTED_MODEL8_NAME}"

  log "Step 5: Install runtime libs"
  install_runtime_libs "${LOCAL_LIBS_DIR}"

  log "Step 6: Install app APK"
  install_app_apk "${APK_PATH}"

  log "Step 7: Rebooting device"
  if [[ -n "${SERIAL}" ]]; then
    adb -s "${SERIAL}" reboot || warn "adb reboot failed"
  else
    adb reboot || warn "adb reboot failed"
  fi

  log "Step 8: Waiting for reboot to complete"
  wait_for_device_and_boot

  log "Step 9: Re-acquire root and disable SELinux"
  if ! adb_run root >/dev/null 2>&1; then
    warn "adb root may not be supported on this build; continuing."
  fi
  adb_run shell setenforce 0 || warn "Failed to setenforce 0; SELinux may remain enforcing."

  log "All steps completed successfully."
}

main "$@"