#!/usr/bin/env python3

import argparse
import json
import os
import sys
import datetime
from typing import Any, Dict, List, Optional, Tuple, Union

try:
	from PIL import Image, ExifTags, JpegImagePlugin
except Exception as e:
	print("Error: Pillow is required. Please install dependencies (pip install -r requirements.txt).", file=sys.stderr)
	raise

try:
	import exifread  # type: ignore
except Exception:
	exifread = None  # Optional; Pillow covers most EXIF

try:
	import xmltodict  # type: ignore
except Exception:
	xmltodict = None


def _safe_int(value: Any, default: int = 0) -> int:
	try:
		return int(value)
	except Exception:
		return default


def _decode_bytes(value: Any) -> Any:
	if isinstance(value, bytes):
		try:
			return value.decode('utf-8', errors='replace')
		except Exception:
			return value.hex()
	return value


def _rational_to_float(r: Any) -> Optional[float]:
	try:
		# Pillow Exif returns IFDRational; exifread returns Ratio
		num = getattr(r, 'numerator', None)
		den = getattr(r, 'denominator', None)
		if num is None or den is None:
			num, den = r
		return float(num) / float(den) if den else None
	except Exception:
		try:
			return float(r)
		except Exception:
			return None


def _convert_gps_to_degrees(values: List[Any], ref: str) -> Optional[float]:
	if not values or len(values) < 3:
		return None
	deg = _rational_to_float(values[0])
	minute = _rational_to_float(values[1])
	second = _rational_to_float(values[2])
	if deg is None or minute is None or second is None:
		return None
	decimal = deg + (minute / 60.0) + (second / 3600.0)
	if ref in ['S', 'W']:
		decimal = -decimal
	return decimal


def extract_file_info(path: str) -> Dict[str, Any]:
	stat = os.stat(path)
	return {
		"path": os.path.abspath(path),
		"size_bytes": stat.st_size,
		"modified_time": datetime.datetime.fromtimestamp(stat.st_mtime).isoformat(),
		"created_time": datetime.datetime.fromtimestamp(stat.st_ctime).isoformat(),
	}


def extract_image_info(img: Image.Image) -> Dict[str, Any]:
	width, height = img.size
	return {
		"width": width,
		"height": height,
		"format": img.format,
		"mode": img.mode,
	}


def extract_exif_with_pillow(img: Image.Image) -> Dict[str, Any]:
	result: Dict[str, Any] = {}
	try:
		exif = img.getexif()
		if not exif:
			return result
		for tag_id, value in exif.items():
			tag = ExifTags.TAGS.get(tag_id, str(tag_id))
			result[tag] = _decode_bytes(value)
	except Exception:
		pass
	return result


def extract_exif_with_exifread(path: str) -> Dict[str, Any]:
	# exifread can sometimes reveal tags Pillow misses
	if exifread is None:
		return {}
	try:
		with open(path, 'rb') as f:
			# Faster, standard options
			tags = exifread.process_file(f, details=False, strict=True)
			return {k: _decode_bytes(str(v)) for k, v in tags.items()}
	except Exception:
		return {}


def parse_gps_from_exif(exif: Dict[str, Any]) -> Dict[str, Any]:
	gps: Dict[str, Any] = {}
	# Pillow naming
	gps_info = exif.get('GPSInfo')
	if isinstance(gps_info, dict):
		# Keys may be numeric; normalize via ExifTags.GPSTAGS if present
		mapped: Dict[str, Any] = {}
		for k, v in gps_info.items():
			name = ExifTags.GPSTAGS.get(k, str(k)) if isinstance(k, int) else str(k)
			mapped[name] = v
		gps_info = mapped
		lat = gps_info.get('GPSLatitude')
		lat_ref = gps_info.get('GPSLatitudeRef', 'N')
		lon = gps_info.get('GPSLongitude')
		lon_ref = gps_info.get('GPSLongitudeRef', 'E')
		alt = gps_info.get('GPSAltitude')
		if lat and lon:
			gps['latitude'] = _convert_gps_to_degrees(list(lat), str(lat_ref))
			gps['longitude'] = _convert_gps_to_degrees(list(lon), str(lon_ref))
		if alt is not None:
			gps['altitude_m'] = _rational_to_float(alt)
		if 'GPSDateStamp' in gps_info:
			gps['date'] = _decode_bytes(gps_info['GPSDateStamp'])
	return gps


def extract_iptc_from_jpeg(img: Image.Image) -> Dict[str, Any]:
	# Best-effort IPTC via Pillow's APP13
	try:
		if not isinstance(img, Image.Image) or img.format != 'JPEG':
			return {}
		iptc = JpegImagePlugin.getiptcinfo(img)
		if not iptc:
			return {}
		return {str(k): _decode_bytes(v) for k, v in iptc.items()}
	except Exception:
		return {}


def extract_xmp(path: str) -> Union[Dict[str, Any], str, None]:
	# Extract raw XMP packet and parse to dict if xmltodict is available
	try:
		with open(path, 'rb') as f:
			data = f.read()
		s = data.find(b"<x:xmpmeta")
		if s == -1:
			return None
		e = data.find(b"</x:xmpmeta>", s)
		if e == -1:
			return None
		xmp_bytes = data[s:e + len(b"</x:xmpmeta>")]
		xmp_text = xmp_bytes.decode('utf-8', errors='replace')
		if xmltodict is None:
			return xmp_text
		try:
			return xmltodict.parse(xmp_text)
		except Exception:
			return xmp_text
	except Exception:
		return None


def extract_metadata_for_file(path: str) -> Dict[str, Any]:
	result: Dict[str, Any] = {
		"file": extract_file_info(path),
	}
	try:
		with Image.open(path) as img:
			result["image"] = extract_image_info(img)
			pillow_exif = extract_exif_with_pillow(img)
			result["exif_pillow"] = pillow_exif
			gps = parse_gps_from_exif(pillow_exif)
			if gps:
				result["gps"] = gps
			iptc = extract_iptc_from_jpeg(img)
			if iptc:
				result["iptc"] = iptc
	except Exception as e:
		result["image_error"] = str(e)

	# exifread second-pass
	exif2 = extract_exif_with_exifread(path)
	if exif2:
		result["exif_extra"] = exif2

	xmp = extract_xmp(path)
	if xmp is not None:
		result["xmp"] = xmp

	# Quick camera info aggregation
	camera_make = None
	camera_model = None
	if isinstance(result.get("exif_pillow"), dict):
		camera_make = result["exif_pillow"].get("Make")
		camera_model = result["exif_pillow"].get("Model")
	result["camera"] = {"make": camera_make, "model": camera_model}
	return result


def walk_image_files(paths: List[str], recursive: bool) -> List[str]:
	valid_ext = {'.jpg', '.jpeg', '.png', '.tif', '.tiff', '.webp', '.bmp', '.gif', '.heic', '.heif', '.dng', '.cr2', '.nef', '.arw'}
	files: List[str] = []
	for p in paths:
		if os.path.isdir(p):
			if recursive:
				for root, _, filenames in os.walk(p):
					for name in filenames:
						if os.path.splitext(name.lower())[1] in valid_ext:
							files.append(os.path.join(root, name))
			else:
				for name in os.listdir(p):
					fp = os.path.join(p, name)
					if os.path.isfile(fp) and os.path.splitext(name.lower())[1] in valid_ext:
						files.append(fp)
		elif os.path.isfile(p):
			ext = os.path.splitext(p.lower())[1]
			if ext in valid_ext:
				files.append(p)
	return files


def main() -> None:
	parser = argparse.ArgumentParser(description="Image metadata inspector (file info, EXIF, GPS, IPTC, XMP)")
	parser.add_argument('paths', nargs='+', help='Image file(s) or directory(ies)')
	parser.add_argument('-r', '--recursive', action='store_true', help='Recurse into directories')
	parser.add_argument('-p', '--pretty', action='store_true', help='Pretty-print JSON')
	args = parser.parse_args()

	files = walk_image_files(args.paths, args.recursive)
	if not files:
		print("No image files found.", file=sys.stderr)
		sys.exit(1)

	results = []
	for fpath in files:
		try:
			results.append(extract_metadata_for_file(fpath))
		except Exception as e:
			results.append({"file": {"path": os.path.abspath(fpath)}, "error": str(e)})

	if args.pretty:
		print(json.dumps(results, ensure_ascii=False, indent=2))
	else:
		print(json.dumps(results, ensure_ascii=False))


if __name__ == '__main__':
	main()