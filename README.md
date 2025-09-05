# Image Metadata CLI

A simple Python tool to read image metadata (file info, dimensions, EXIF with GPS, IPTC, and XMP) and output JSON.

## Install

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
```

## Usage

```bash
python image_meta.py /path/to/image.jpg --pretty
python image_meta.py /path/to/dir -r --pretty
```

- Outputs a JSON array of objects with keys: `file`, `image`, `exif_pillow`, `exif_extra`, `gps`, `iptc`, `xmp`, `camera` (when available).
- `--pretty` prints human-readable JSON.

## Notes
- EXIF GPS requires GPS tags present in the image.
- IPTC is best-effort via JPEG APP13; may be empty on non-JPEG formats.
- XMP is extracted as raw XML text if `xmltodict` isn't available; otherwise parsed to a dict.