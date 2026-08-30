#!/usr/bin/env python3
"""Dataset utilities: bucket parsing and image collection.

Python counterpart of LandmarkerTest.parseBucket() in
app/src/androidTest/kotlin/isao/photorate/android/LandmarkerTest.kt

Bucket expectations (identical to Kotlin):
- confident_N -> must detect gesture with score N, confidence > 0
- uncertain_N -> must detect gesture with exact score N AND confidence == 0
- rejected    -> must NOT detect any gesture
- *_temp_disabled -> excluded

Reads directly from app/src/androidTest/assets/ — single source of truth,
no more bucket copies (replaces ml/litert/scripts/test/copy_images.py).
"""
from __future__ import annotations

import re
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[3]
ASSETS_DIR = PROJECT_ROOT / "app" / "src" / "androidTest" / "assets"

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png"}


class BucketConfig:
    def __init__(self, name, expect, score=0):
        self.name = name
        self.expect = expect
        self.score = score


def parse_bucket(name):
    """Parse directory name into test expectations (mirrors LandmarkerTest.parseBucket)."""
    if name == "rejected":
        return BucketConfig(name, "filter")
    m = re.match(r"^(confident|uncertain)_(\d+)$", name)
    if m:
        level, score = m.group(1), int(m.group(2))
        expect = "uncertain" if level == "uncertain" else "detect"
        return BucketConfig(name, expect, score)
    return None


def is_temp_disabled(name):
    return name.endswith("_temp_disabled")


def collect_buckets():
    """Return list of (BucketConfig, [image_path, ...]) from androidTest assets."""
    buckets = []
    if not ASSETS_DIR.exists():
        return buckets
    for d in sorted(ASSETS_DIR.iterdir()):
        if not d.is_dir():
            continue
        if is_temp_disabled(d.name):
            continue
        config = parse_bucket(d.name)
        if config is None:
            continue
        images = sorted(
            p for p in d.iterdir()
            if p.is_file() and p.suffix.lower() in IMAGE_EXTENSIONS
        )
        if images:
            buckets.append((config, images))
    return buckets


def collect_images(target):
    """Resolve user-provided target (file or directory) to a list of image paths."""
    paths = []
    p = Path(target)
    if not p.exists():
        return paths
    if p.is_file() and p.suffix.lower() in IMAGE_EXTENSIONS:
        paths.append(p)
    elif p.is_dir():
        for ext in IMAGE_EXTENSIONS:
            paths.extend(sorted(p.rglob(f"*{ext}")))
    return paths
