#!/usr/bin/env python3
"""Run the hand landmarking pipeline on images and dump results.

Usage:
    python3 ml/litert/scripts/test/run_landmarks.py images/confident_5/
    python3 ml/litert/scripts/test/run_landmarks.py images/rejected/
    python3 ml/litert/scripts/test/run_landmarks.py images/
"""
import argparse
import json
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent.parent.parent.parent
sys.path.insert(0, str(PROJECT_ROOT / "plans" / "benchmarks"))
from rtmpose_only_v5 import decode_640, rtmdet_boxes, run_pipeline


def process_image(path: Path) -> dict:
    img = decode_640(path)
    if img is None:
        return {"file": path.name, "error": "decode_failed", "hands": []}

    boxes = rtmdet_boxes(img)
    results = run_pipeline(img, boxes, use_palm=True)

    hands = []
    for i, (label, winner) in enumerate(results):
        hand_info = {"box_index": i, "label": label}
        if winner is not None:
            hand_info["gesture"] = winner["cls"][0] if winner["cls"] else None
            hand_info["score"] = winner["cls"][1] if winner["cls"] else None
            hand_info["kp_mean"] = round(winner["kp_mean"], 3)
            hand_info["rotation"] = winner["deg"]
        hands.append(hand_info)

    return {"file": path.name, "hands": hands}


def resolve_paths(targets: list[str]) -> list[Path]:
    paths = []
    for target in targets:
        p = Path(target)
        if p.exists():
            if p.is_file() and p.suffix.lower() in (".jpg", ".jpeg", ".png"):
                paths.append(p)
            elif p.is_dir():
                for ext in ("*.jpg", "*.jpeg", "*.png"):
                    paths.extend(sorted(p.rglob(ext)))
            continue
        script_rel = SCRIPT_DIR / target
        if script_rel.exists():
            if script_rel.is_file():
                paths.append(script_rel)
            elif script_rel.is_dir():
                for ext in ("*.jpg", "*.jpeg", "*.png"):
                    paths.extend(sorted(script_rel.rglob(ext)))
            continue
        print(f"Warning: cannot resolve '{target}'", file=sys.stderr)
    return paths


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("target", nargs="+", help="Image, directory, or bucket path")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    all_paths = resolve_paths(args.target)
    if not all_paths:
        print("No images found.", file=sys.stderr)
        sys.exit(1)

    all_results = [process_image(p) for p in all_paths]

    if args.json:
        print(json.dumps(all_results, indent=2))
    else:
        for result in all_results:
            hands = result.get("hands", [])
            if not hands:
                status = result.get("error", "no_hands")
                print(f"  {result['file']:<50} [{status}]")
            else:
                labels = []
                for h in hands:
                    g, s = h.get("gesture"), h.get("score")
                    kp, rot = h.get("kp_mean", 0), h.get("rotation", 0)
                    labels.append(f"{kp:.2f}@{int(rot)}/{g}-{s}" if g else f"no-gesture@{int(rot)}")
                print(f"  {result['file']:<50} [{'; '.join(labels)}]")

    print(f"\n{len(all_results)} images processed")


if __name__ == "__main__":
    main()
