#!/usr/bin/env bash
set -euo pipefail

# Map Windows UNC shares to macOS mount points under /Volumes
# Override via env vars if your mounts differ
WINE_ROOT="${WINE_ROOT:-/Volumes/China_CE}"
VOICEUI_ROOT="${VOICEUI_ROOT:-/Volumes/VoiceUI}"
QRDSHARE_ROOT="${QRDSHARE_ROOT:-/Volumes/qrdshare}"

ASR_DIR="${WINE_ROOT}/Multimedia/Audio/00_liyang/asr"
TTS_MODELS_DIR="${VOICEUI_ROOT}/13_VoiceAI/TTS_Models/AUDIO_SYSTEM_TTS_Notebook_1.0_1.4.0_Tejas"
TTS_ENGINE_MODELS_DIR="${QRDSHARE_ROOT}/Dropbox/VoiceAI/internal/Android/TTSEngine/models"

echo "Using sources:"
echo "  WINE_ROOT=${WINE_ROOT}"
echo "  VOICEUI_ROOT=${VOICEUI_ROOT}"
echo "  QRDSHARE_ROOT=${QRDSHARE_ROOT}"

echo "[1/6] adb root/wait/remount"
adb root
adb wait-for-device
adb remount

echo "[2/6] Push ASR model"
adb push "${ASR_DIR}/zipformer.250221.cn.8w16a.eai_v5.7.enpu_v6.offload.eai" \
  "/data/data/com.qualcomm.qti.voiceai.usecase/files/asr_model.eai"

echo "[3/6] Replace audio HAL lib"
adb push "${ASR_DIR}/lib/libstream_asr.so" "/vendor/lib64/"

echo "[4/6] Push TTS models"
adb shell mkdir -p "/vendor/etc/tts"
adb push "${TTS_MODELS_DIR}/." "/vendor/etc/tts/"

echo "[5/6] Push TTS engine .so files"
adb push "${TTS_ENGINE_MODELS_DIR}/libQnnHtp.so" "/vendor/etc/tts/"
adb push "${TTS_ENGINE_MODELS_DIR}/libQnnHtpV81.so" "/vendor/etc/tts/"
adb push "${TTS_ENGINE_MODELS_DIR}/libQnnSystem.so" "/vendor/etc/tts/"
adb push "${TTS_ENGINE_MODELS_DIR}/libtts_impl_skel.so" "/vendor/etc/tts/"

echo "[6/6] Update ACDB and XML files"
adb push "${ASR_DIR}/acdb_xml/QRD_canoe_wsa884x_workspaceFileXml.qwsp" \
  "/vendor/etc/acdbdata/canoe_qrd_wsa884x/"
adb push "${ASR_DIR}/acdb_xml/QRD_canoe_wsa884x_acdb_cal.acdb" \
  "/vendor/etc/acdbdata/canoe_qrd_wsa884x/"
adb push "${ASR_DIR}/acdb_xml/resourcemanager_canoe_qrd_wsa884x.xml" \
  "/vendor/etc/audio/sku_canoe/"
adb push "${ASR_DIR}/acdb_xml/mixer_paths_canoe_qrd_wsa884x.xml" \
  "/vendor/etc/audio/sku_canoe/"

adb push "${ASR_DIR}/acdb_xml/QRD_workspaceFileXml.qwsp" \
  "/vendor/etc/acdbdata/canoe_qrd/"
adb push "${ASR_DIR}/acdb_xml/QRD_acdb_cal.acdb" \
  "/vendor/etc/acdbdata/canoe_qrd/"
adb push "${ASR_DIR}/acdb_xml/resourcemanager_canoe_qrd.xml" \
  "/vendor/etc/audio/sku_canoe/"
adb push "${ASR_DIR}/acdb_xml/mixer_paths_canoe_qrd.xml" \
  "/vendor/etc/audio/sku_canoe/"

adb push "${ASR_DIR}/acdb_xml/ffv__14.0.1.1_0.0__eai_5.7_enpu_v6.pmd" \
  "/vendor/etc/acdbdata/ffv_models/"

adb shell sync
adb reboot

echo "Done. Device rebooted."

