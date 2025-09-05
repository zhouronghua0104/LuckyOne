# Image Metadata CLI

A simple Python tool to read image metadata (file info, dimensions, EXIF with GPS, IPTC, and XMP) and output JSON. It also supports reverse geocoding (coords -> address) using OpenStreetMap Nominatim.

## Install

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
```

## Usage

- Single image file:
```bash
python image_meta.py /path/to/image.jpg --pretty
```

- Directory (recursive):
```bash
python image_meta.py /path/to/dir -r --pretty
```

- Reverse geocode coordinates directly:
```bash
python image_meta.py --reverse-lat 39.96525 --reverse-lon 116.40406 --lang zh-CN --pretty
```

- Add reverse geocoded address to images that have GPS:
```bash
python image_meta.py /path/to/dir -r --reverse --lang zh-CN --pretty
```

Notes:
- `--lang` controls the preferred language of the returned address (e.g., `zh-CN`).
- `--nominatim-email` can be provided to comply with Nominatim usage policy.
- Output includes keys: `file`, `image`, `exif_pillow`, `exif_extra`, `gps`, `iptc`, `xmp`, `camera`, and optionally `address`.