#!/usr/bin/env python3
"""A/B the two RTMDet letterbox conventions against each other.

Finds samples where the official mmdet test pipeline (pad 114, centered) and
the app's convention (pad 0, top-left) disagree on the TOP detections — the
letterbox alignment is the one variable that changed detection scores the
most on the shipped rtmdet_n_hand.onnx (see README.md "Findings").

Usage:
  onnx/.venv/bin/python plans/rtmpose_hand/compare_letterbox.py
  onnx/.venv/bin/python plans/rtmpose_hand/compare_letterbox.py --input-dir plans/samples --det-thr 0.25
"""
import argparse
import sys
from pathlib import Path

from overlay_hands import decode_image, detect_boxes, load_sessions

HERE = Path(__file__).resolve().parent
DEFAULT_MODELS = HERE.parent.parent / "onnx" / "models"


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--input-dir", type=Path, default=HERE.parent / "samples")
    p.add_argument("--models-dir", type=Path, default=DEFAULT_MODELS)
    p.add_argument("--det-thr", type=float, default=0.25,
                   help="the app's minimum detection confidence")
    args = p.parse_args(argv)

    det, _ = load_sessions(args.models_dir)
    files = sorted(f for f in args.input_dir.rglob("*")
                   if f.suffix.lower() in (".jpg", ".jpeg", ".png"))

    print(f"{'image':<42} {'official (114/center)':<28} {'app (0/top-left)':<28} verdict")
    print("-" * 130)
    n_missed = 0
    for f in files:
        rel = str(f.relative_to(args.input_dir))
        img = decode_image(f)
        official = detect_boxes(img, det, pad_val=114, center_pad=True)
        app = detect_boxes(img, det, pad_val=0, center_pad=False)
        off = "; ".join(f"{b[4]:.2f}" for b in official[:2]) or "-"
        ap = "; ".join(f"{b[4]:.2f}" for b in app[:2]) or "-"
        top_off = official[0][4] if official else 0.0
        top_app = app[0][4] if app else 0.0
        verdict = ""
        if top_off < args.det_thr <= top_app:
            verdict = "OFFICIAL MISSES (app finds hand)"
            n_missed += 1
        elif top_app < args.det_thr <= top_off:
            verdict = "APP MISSES (official finds hand)"
        elif abs(top_off - top_app) > 0.05:
            verdict = f"score delta {top_off - top_app:+.2f}"
        print(f"{rel:<42} {off:<28} {ap:<28} {verdict}")
    print(f"\nsamples the official letterbox would miss at det-thr "
          f"{args.det_thr}: {n_missed}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
