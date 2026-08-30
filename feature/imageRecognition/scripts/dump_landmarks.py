#!/usr/bin/env python3
"""Dump per-image landmark analysis as JSON + optional skeleton overlay.

Python counterpart of ml/litert/scripts/test/run_landmarks.py (the replacement).
Uses the same Pipeline backends as test_landmarks_regression.py.

Usage:
    python3 dump_landmarks.py <image-or-dir> [--backend onnx|tflite] [--overlay] [--json out.json]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS_DIR))

from rtm_pipeline import Pipeline, decode_image
from gesture_classify import create_recognizers
from dataset import collect_images

SKELETON = [
    (0, 1), (1, 2), (2, 3), (3, 4),
    (0, 5), (5, 6), (6, 7), (7, 8),
    (0, 9), (9, 10), (10, 11), (11, 12),
    (0, 13), (13, 14), (14, 15), (15, 16),
    (0, 17), (18, 19), (19, 20),
    (0, 17), (17, 18),
]


def draw_overlay(img, hands, out_path):
    import cv2

    vis = img.copy()
    colors = [
        (255, 255, 255),
        (0, 128, 255), (0, 128, 255), (0, 128, 255), (0, 128, 255),
        (255, 153, 255), (255, 153, 255), (255, 153, 255), (255, 153, 255),
        (255, 178, 102), (255, 178, 102), (255, 178, 102), (255, 178, 102),
        (51, 51, 255), (51, 51, 255), (51, 51, 255), (51, 51, 255),
        (0, 255, 0), (0, 255, 0), (0, 255, 0), (0, 255, 0),
    ]
    for h in hands:
        pts = h["points"]
        for a, b in SKELETON:
            if a < len(pts) and b < len(pts):
                pa, pb = pts[a], pts[b]
                if pa[2] > 0.3 and pb[2] > 0.3:
                    cv2.line(
                        vis,
                        (int(pa[0]), int(pa[1])),
                        (int(pb[0]), int(pb[1])),
                        colors[a], 2, cv2.LINE_AA,
                    )
        for i, (x, y, c) in enumerate(pts):
            color = colors[i] if i < len(colors) else (255, 255, 255)
            if c < 0.3:
                cv2.circle(vis, (int(x), int(y)), 3, (60, 60, 60), 1, cv2.LINE_AA)
            else:
                cv2.circle(vis, (int(x), int(y)), 4, color, -1, cv2.LINE_AA)
        box = h.get("box")
        if box:
            cv2.rectangle(vis, (box[0], box[1]), (box[2], box[3]), (255, 255, 255), 1)
        label = f"{h['gesture'] or 'none'}-{h['score']} kp={h['kpMean']:.2f}"
        cv2.putText(vis, label, (box[0] if box else 10, (box[1] - 5) if box else 25),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 255), 1, cv2.LINE_AA)
    cv2.imwrite(str(out_path), vis)
    print(f"  overlay -> {out_path}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", help="Image file or directory")
    parser.add_argument("--backend", choices=["onnx", "tflite"], default="onnx")
    parser.add_argument("--overlay", action="store_true", help="Draw skeleton overlay")
    parser.add_argument("--output-dir", type=Path, default=None)
    parser.add_argument("--json", dest="json_out", type=Path, default=None)
    args = parser.parse_args()

    images = collect_images(args.target)
    if not images:
        print(f"No images found for target: {args.target}", file=sys.stderr)
        sys.exit(1)

    pipe = Pipeline(backend=args.backend)
    recognizers = create_recognizers()

    all_results = []
    for img_path in images:
        img = decode_image(img_path)
        if img is None:
            result = {"file": str(img_path), "error": "decode_failed", "hands": []}
        else:
            hands = pipe.detect_with_recognizers(img, recognizers)
            result = {
                "file": str(img_path),
                "backend": args.backend,
                "hands": [
                    {
                        "gesture": h["gesture"],
                        "score": h["score"],
                        "confidence": round(h["confidence"], 4),
                        "kpMean": h["kpMean"],
                        "detScore": h["detScore"],
                        "rotation": h["rotation"],
                        "box": h["box"],
                        "points": [
                            (round(p[0], 2), round(p[1], 2), round(p[2], 4))
                            for p in h["points"]
                        ],
                    }
                    for h in hands
                ],
            }

            if args.overlay:
                out_dir = args.output_dir or (SCRIPTS_DIR / "output")
                out_dir.mkdir(parents=True, exist_ok=True)
                out_path = out_dir / f"{img_path.stem}_overlay.jpg"
                draw_overlay(img, hands, out_path)

        all_results.append(result)

        hands_summary = "; ".join(
            f"{h['gesture'] or 'none'}-{h['score']} conf={h['confidence']:.2f} kp={h['kpMean']:.2f}"
            for h in result.get("hands", [])
        ) or "no hands"
        print(f"  {img_path.name}: {hands_summary}")

    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        with open(args.json_out, "w", encoding="utf-8") as f:
            json.dump(all_results, f, ensure_ascii=False, indent=2)
        print(f"\nResults written to {args.json_out}")

    print(f"\n{len(all_results)} images processed")


if __name__ == "__main__":
    sys.exit(main())
