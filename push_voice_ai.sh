#!/usr/bin/env bash
set -euo pipefail

# Resolve script directory so relative paths work from anywhere
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "[1/6] adb root/wait/remount"
adb root
adb wait-for-device
adb remount

echo "[2/6] Push ASR model"
adb push "${SCRIPT_DIR}/asr/zipformer.250221.cn.8w16a.eai_v5.7.enpu_v6.offload.eai" \
  "/data/data/com.qualcomm.qti.voiceai.usecase/files/asr_model.eai"

echo "[3/6] Replace audio HAL lib"
adb push "${SCRIPT_DIR}/asr/lib/libstream_asr.so" "/vendor/lib64/"

echo "[4/6] Push TTS models"
adb shell mkdir -p "/vendor/etc/tts"
# Push contents of the Tejas directory into /vendor/etc/tts/
adb push "${SCRIPT_DIR}/VoiceAI/TTS_Models/AUDIO_SYSTEM_TTS_Notebook_1.0_1.4.0_Tejas/." "/vendor/etc/tts/"

echo "[5/6] Push TTS engine .so files"
adb push "${SCRIPT_DIR}/VoiceAI/TTSEngine/models/libQnnHtp.so" "/vendor/etc/tts/"
adb push "${SCRIPT_DIR}/VoiceAI/TTSEngine/models/libQnnHtpV81.so" "/vendor/etc/tts/"
adb push "${SCRIPT_DIR}/VoiceAI/TTSEngine/models/libQnnSystem.so" "/vendor/etc/tts/"
adb push "${SCRIPT_DIR}/VoiceAI/TTSEngine/models/libtts_impl_skel.so" "/vendor/etc/tts/"

echo "[6/6] Update ACDB and XML files"
adb push "${SCRIPT_DIR}/asr/acdb_xml/QRD_canoe_wsa884x_workspaceFileXml.qwsp" \
  "/vendor/etc/acdbdata/canoe_qrd_wsa884x/"
adb push "${SCRIPT_DIR}/asr/acdb_xml/QRD_canoe_wsa884x_acdb_cal.acdb" \
  "/vendor/etc/acdbdata/canoe_qrd_wsa884x/"
adb push "${SCRIPT_DIR}/asr/acdb_xml/resourcemanager_canoe_qrd_wsa884x.xml" \
  "/vendor/etc/audio/sku_canoe/"
adb push "${SCRIPT_DIR}/asr/acdb_xml/mixer_paths_canoe_qrd_wsa884x.xml" \
  "/vendor/etc/audio/sku_canoe/"

adb push "${SCRIPT_DIR}/asr/acdb_xml/QRD_workspaceFileXml.qwsp" \
  "/vendor/etc/acdbdata/canoe_qrd/"
adb push "${SCRIPT_DIR}/asr/acdb_xml/QRD_acdb_cal.acdb" \
  "/vendor/etc/acdbdata/canoe_qrd/"
adb push "${SCRIPT_DIR}/asr/acdb_xml/resourcemanager_canoe_qrd.xml" \
  "/vendor/etc/audio/sku_canoe/"
adb push "${SCRIPT_DIR}/asr/acdb_xml/mixer_paths_canoe_qrd.xml" \
  "/vendor/etc/audio/sku_canoe/"

adb push "${SCRIPT_DIR}/asr/acdb_xml/ffv__14.0.1.1_0.0__eai_5.7_enpu_v6.pmd" \
  "/vendor/etc/acdbdata/ffv_models/"

adb shell sync
adb reboot

echo "Done. Device rebooted."

