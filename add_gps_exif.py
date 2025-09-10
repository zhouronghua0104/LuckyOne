#!/usr/bin/env python3

"""
Add GPS EXIF metadata (latitude/longitude, optional altitude) to JPEG/TIFF using piexif.

Usage:
  python3 add_gps_exif.py --image <path> --lat <decimal> --lon <decimal> [--alt <meters>] [--datetime "YYYY:MM:DD HH:MM:SS"] [--overwrite]

Notes:
  - Works for JPEG/TIFF. For HEIC/PNG and wider format support, use exiftool (see add_gps_exif.sh).
  - Install deps: pip install piexif Pillow
"""

import argparse
import os
import sys
from typing import Optional

import piexif
from PIL import Image


def to_rational(value: float):
    from fractions import Fraction
    frac = Fraction(value).limit_denominator(1000000)
    return (frac.numerator, frac.denominator)


def deg_to_dms_rationals(decimal_degrees: float):
    sign = -1 if decimal_degrees < 0 else 1
    abs_val = abs(decimal_degrees)
    degrees = int(abs_val)
    minutes_full = (abs_val - degrees) * 60.0
    minutes = int(minutes_full)
    seconds = (minutes_full - minutes) * 60.0
    return (
        to_rational(degrees),
        to_rational(minutes),
        to_rational(seconds),
        sign,
    )


def build_gps_ifd(lat: float, lon: float, alt: Optional[float]):
    lat_deg, lat_min, lat_sec, lat_sign = deg_to_dms_rationals(lat)
    lon_deg, lon_min, lon_sec, lon_sign = deg_to_dms_rationals(lon)

    gps_ifd = {
        piexif.GPSIFD.GPSLatitudeRef: b"S" if lat_sign < 0 else b"N",
        piexif.GPSIFD.GPSLatitude: [lat_deg, lat_min, lat_sec],
        piexif.GPSIFD.GPSLongitudeRef: b"W" if lon_sign < 0 else b"E",
        piexif.GPSIFD.GPSLongitude: [lon_deg, lon_min, lon_sec],
        piexif.GPSIFD.GPSVersionID: (2, 3, 0, 0),
    }

    if alt is not None:
        gps_ifd[piexif.GPSIFD.GPSAltitudeRef] = 1 if alt < 0 else 0
        gps_ifd[piexif.GPSIFD.GPSAltitude] = to_rational(abs(alt))

    return gps_ifd


def main():
    parser = argparse.ArgumentParser(description="Add GPS EXIF to JPEG/TIFF")
    parser.add_argument("--image", required=True)
    parser.add_argument("--lat", type=float, required=True)
    parser.add_argument("--lon", type=float, required=True)
    parser.add_argument("--alt", type=float, default=None)
    parser.add_argument("--datetime", type=str, default=None, help="YYYY:MM:DD HH:MM:SS")
    parser.add_argument("--overwrite", action="store_true")
    args = parser.parse_args()

    image_path = args.image
    if not os.path.isfile(image_path):
        print(f"Error: image not found: {image_path}", file=sys.stderr)
        sys.exit(1)

    try:
        with Image.open(image_path) as img:
            img.verify()
    except Exception as exc:
        print(f"Error: cannot open image or unsupported format: {exc}", file=sys.stderr)
        sys.exit(1)

    try:
        exif_dict = {}
        ifd = build_gps_ifd(args.lat, args.lon, args.alt)

        # Load existing EXIF if present
        try:
            exif_dict = piexif.load(image_path)
        except Exception:
            exif_dict = {"0th": {}, "Exif": {}, "GPS": {}, "1st": {}, "thumbnail": None}

        exif_dict.setdefault("GPS", {})
        exif_dict["GPS"].update(ifd)

        if args.datetime:
            exif_dict.setdefault("Exif", {})
            exif_dict["Exif"][piexif.ExifIFD.DateTimeOriginal] = args.datetime.encode("ascii")

        exif_bytes = piexif.dump(exif_dict)

        if args.overwrite:
            piexif.insert(exif_bytes, image_path)
            output_path = image_path
        else:
            root, ext = os.path.splitext(image_path)
            output_path = f"{root}_gps{ext}"
            piexif.insert(exif_bytes, image_path, output_path)

        print(f"Wrote GPS EXIF to: {output_path}")
    except Exception as exc:
        print(f"Failed to write EXIF: {exc}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()

