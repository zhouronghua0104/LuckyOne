#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<EOF
Add GPS EXIF metadata (latitude/longitude, optional altitude) to an image using exiftool.

Usage:
  $(basename "$0") --image <path> --lat <decimal> --lon <decimal> [--alt <meters>] [--datetime "YYYY:MM:DD HH:MM:SS"] [--overwrite]

Examples:
  $(basename "$0") --image photo.jpg --lat 39.9042 --lon 116.4074
  $(basename "$0") --image IMG_0001.HEIC --lat -33.8688 --lon 151.2093 --alt 58 --overwrite

Notes:
  - Requires exiftool (macOS: brew install exiftool)
  - Signed decimals are supported. Use negative latitude for south, negative longitude for west.
  - Without --overwrite, exiftool will create a backup (filename.jpg_original).
EOF
}

if [[ $# -eq 0 ]]; then
  usage
  exit 1
fi

if ! command -v exiftool >/dev/null 2>&1; then
  echo "Error: exiftool not found. Install via Homebrew: brew install exiftool" >&2
  exit 1
fi

IMAGE=""
LAT=""
LON=""
ALT=""
DATETIME=""
OVERWRITE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    -i|--image)
      IMAGE="${2:-}"
      shift 2
      ;;
    --lat)
      LAT="${2:-}"
      shift 2
      ;;
    --lon)
      LON="${2:-}"
      shift 2
      ;;
    --alt)
      ALT="${2:-}"
      shift 2
      ;;
    --datetime)
      DATETIME="${2:-}"
      shift 2
      ;;
    --overwrite)
      OVERWRITE=1
      shift 1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$IMAGE" || -z "$LAT" || -z "$LON" ]]; then
  echo "Error: --image, --lat and --lon are required." >&2
  usage
  exit 1
fi

if [[ ! -f "$IMAGE" ]]; then
  echo "Error: image not found: $IMAGE" >&2
  exit 1
fi

# Basic numeric validation
if ! [[ "$LAT" =~ ^-?[0-9]+(\.[0-9]+)?$ ]]; then
  echo "Error: --lat must be a decimal number." >&2
  exit 1
fi
if ! [[ "$LON" =~ ^-?[0-9]+(\.[0-9]+)?$ ]]; then
  echo "Error: --lon must be a decimal number." >&2
  exit 1
fi
if [[ -n "$ALT" ]] && ! [[ "$ALT" =~ ^-?[0-9]+(\.[0-9]+)?$ ]]; then
  echo "Error: --alt must be a decimal number (meters)." >&2
  exit 1
fi

EXIFTOOL_FLAGS=(
  -n
  "-GPSLatitude=$LAT"
  "-GPSLongitude=$LON"
)

if [[ -n "$ALT" ]]; then
  EXIFTOOL_FLAGS+=("-GPSAltitude=$ALT")
fi

if [[ -n "$DATETIME" ]]; then
  EXIFTOOL_FLAGS+=("-DateTimeOriginal=$DATETIME")
fi

if [[ $OVERWRITE -eq 1 ]]; then
  EXIFTOOL_FLAGS+=(-overwrite_original)
fi

exiftool "${EXIFTOOL_FLAGS[@]}" "$IMAGE" | cat

echo "\nDone. Current GPS metadata:"
exiftool -s -GPS:All "$IMAGE" | cat

