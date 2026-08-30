#!/usr/bin/env python3
"""Compare device logcat output from LiteRtHandLandmarkerTest against the
Python regression JSON manifest.

Kotlin counterpart (KDoc links both ways):
- app/src/androidTest/kotlin/isao/photorate/android/LiteRtHandLandmarkerTest.kt (logTag)
- app/src/androidTest/kotlin/isao/photorate/android/LandmarkerTest.kt (log format)

The Kotlin test logs each image as:
    <logTag> <bucketDir>/<file>|OK: [<Gesture>-<score>]
    <logTag> <bucketDir>/<file>|FAIL: expected score N ..., got [...]
with tag = logTag (e.g. "LiteRtHandLandmarkerTest").

This script:
1. Parses logcat lines (from a file or stdin) into per-image device results.
2. Loads the Python regression JSON (test_landmarks_regression.py --json).
3. Diffs per-image: bucket/file -> (pass/fail, detected scores).
4. Reports images where Python and device disagree, so pipeline drift is
   attributable to the runtime (conversion / model graph) rather than the
   classifier logic (which is shared and verified identical by unit tests).

Usage:
    adb logcat -d -s LiteRtHandLandmarkerTest | python3 compare_device_log.py --json results.json
    python3 compare_device_log.py --logcat device.log --json results.json --backend tflite
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

# Maps Kotlin gesture class simple names to Python gesture strings.
KOTLIN_GESTURE_MAP = {
    "ThumbSignal": "THUMBS_UP",
    "ThumbOnlyGesture": "THUMBS_UP",
    "OkSign": "OK_SIGN",
}

LINE_RE = re.compile(
    r"(?P<bucket>[^/\s]+)/(?P<file>[^|]+)\|(?P<status>OK|FAIL):?\s*(?P<detail>.*)"
)
SCORE_RE = re.compile(r"(\w+)-(\d+)")


def parse_logcat(lines):
    """Parse logcat lines into {(bucket, file): {"passed": bool, "scores": [(gesture, score)]}}."""
    device = {}
    for raw in lines:
        line = raw.rstrip("\n")
        if "|" not in line:
            continue
        m = LINE_RE.search(line)
        if not m:
            continue
        bucket = m.group("bucket")
        file = m.group("file").strip()
        status = m.group("status")
        detail = m.group("detail").strip()

        scores = []
        for gm in SCORE_RE.finditer(detail):
            gesture_kotlin = gm.group(1)
            score = int(gm.group(2))
            gesture = KOTLIN_GESTURE_MAP.get(gesture_kotlin, gesture_kotlin)
            scores.append((gesture, score))

        key = (bucket, file)
        device[key] = {
            "passed": status == "OK",
            "scores": scores,
            "detail": detail,
        }
    return device


def load_python_results(json_path):
    """Load the Python regression JSON into {(bucket, file): {"passed", "scores"}}."""
    with open(json_path, encoding="utf-8") as f:
        data = json.load(f)
    py = {}
    for entry in data:
        key = (entry["bucket"], entry["file"])
        scores = [
            (h["gesture"], h["score"])
            for h in entry.get("hands", [])
            if h.get("gesture") is not None
        ]
        py[key] = {"passed": entry.get("passed", False), "scores": scores}
    return py


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", dest="json_path", type=Path, required=True,
                        help="Python regression results JSON (test_landmarks_regression.py --json)")
    parser.add_argument("--logcat", type=Path, default=None,
                        help="logcat capture file; defaults to stdin")
    parser.add_argument("--backend", default="tflite",
                        help="Backend label for the report header")
    args = parser.parse_args()

    if args.logcat:
        lines = args.logcat.read_text(encoding="utf-8", errors="replace").splitlines()
    else:
        lines = sys.stdin.read().splitlines()

    device = parse_logcat(lines)
    python = load_python_results(args.json_path)

    if not device:
        print("No device results parsed from logcat input.", file=sys.stderr)
        print("Expected lines like: LiteRtHandLandmarkerTest confident_5/x.jpg|OK: [ThumbSignal-5]",
              file=sys.stderr)
        return 1

    if not python:
        print(f"No Python results loaded from {args.json_path}.", file=sys.stderr)
        return 1

    all_keys = sorted(set(device.keys()) | set(python.keys()))
    agree = 0
    disagree = []
    device_only = []
    python_only = []

    for key in all_keys:
        bucket, file = key
        d = device.get(key)
        p = python.get(key)
        if d is None:
            python_only.append(key)
            continue
        if p is None:
            device_only.append(key)
            continue

        d_pass = d["passed"]
        p_pass = p["passed"]
        d_scores = set(d["scores"])
        p_scores = set(p["scores"])

        same_pass = d_pass == p_pass
        same_scores = d_scores == p_scores

        if same_pass and same_scores:
            agree += 1
        else:
            disagree.append({
                "key": key,
                "device": {"passed": d_pass, "scores": sorted(d["scores"])},
                "python": {"passed": p_pass, "scores": sorted(p["scores"])},
            })

    print(f"Backend: {args.backend}")
    print(f"Agree:  {agree}/{len(all_keys)}")
    if disagree:
        print(f"\nDisagreements ({len(disagree)}):")
        for d in disagree:
            bucket, file = d["key"]
            print(f"  {bucket}/{file}")
            print(f"    device: passed={d['device']['passed']} scores={d['device']['scores']}")
            print(f"    python: passed={d['python']['passed']} scores={d['python']['scores']}")
    if device_only:
        print(f"\nDevice-only (missing from Python JSON): {len(device_only)}")
        for k in device_only:
            print(f"  {k[0]}/{k[1]}")
    if python_only:
        print(f"\nPython-only (missing from device log): {len(python_only)}")
        for k in python_only:
            print(f"  {k[0]}/{k[1]}")

    return 0 if not disagree else 1


if __name__ == "__main__":
    sys.exit(main())
