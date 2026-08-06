#!/usr/bin/env python3
"""Rotation-search early-exit analysis (2026-08-04).

Compares the app's FULL rotation search (1.2x/0.85x crop factors x
0/90/180/270, 8 RTMPose runs worst-case) against a deg-0-first EARLY EXIT:
stop the search as soon as the deg-0 read on the 1.2x crop is confident
(kp >= MIN_RTMPOSE_KP). The winner rule already prefers image-space reads at
that bar, so results are provably identical — this script verifies it on all
of plans/samples and reports the RTMPose-run savings.

Findings: identical winners on all 33 samples / 39 boxes, RTMPose runs
204 -> 123 (-40%). The images that still pay the full search (2_coffee,
2_peanuts, no_score_holding_*, 1_pills 2nd hand) have low deg-0 confidence —
they genuinely need the rotations, so the rotation search remains necessary
(6 of 39 boxes get their only useful read from a rotated crop).

Run:  onnx/.venv/bin/python plans/benchmarks/rot_analysis.py

NOTE: `search()` duplicates the app_search loop from overlay_hands.py (with
run counting added). If the app's search changes again, update this copy in
sync with app_search / AndroidOnnxHandLandmarker.rtmposeRating, or it will
silently stop proving equivalence.
"""
import sys
from pathlib import Path

sys.path.insert(0, "plans/rtmpose_hand")
from collections import Counter

from overlay_hands import (BOX_EXPANSION, FALLBACK_FACTORS, MIN_RTMPOSE_KP,
                           NUM_LANDMARKS, ROTATION_SEARCH, app_rotate_and_crop_square,
                           classify_gesture, decode_image, detect_boxes, expand_square,
                           load_sessions, rtmpose_landmarks)

SAMPLES = Path(__file__).resolve().parent.parent / "samples"
MODELS = Path(__file__).resolve().parent.parent.parent / "onnx" / "models"
det, pose = load_sessions(MODELS)


def search(img, box, early_exit=False):
    """Port of app_search with run counting + optional deg-0 early exit.
    Returns (winner_dict, runs, reads)."""
    iw, ih = img.shape[1], img.shape[0]
    image_space = None
    best_rotated = None
    best_kp = None
    runs = 0
    reads = []
    early_used = False
    for factor in FALLBACK_FACTORS:
        cx, cy, side = expand_square(box, iw, ih, factor)
        for deg in ROTATION_SEARCH:
            got = app_rotate_and_crop_square(img, cx, cy, side, deg)
            if got is None:
                continue
            crop, origin = got
            kps = rtmpose_landmarks(crop, pose)
            runs += 1
            kp_mean = float(kps[:, 2].mean())
            pts = [(float(kps[i, 0]), float(kps[i, 1])) for i in range(NUM_LANDMARKS)]
            cls = classify_gesture(pts)
            reads.append((factor, deg, kp_mean, cls))
            entry = (kp_mean, cls, deg, factor)
            if best_kp is None or kp_mean > best_kp[0]:
                best_kp = entry
            if cls is None:
                continue
            if deg == 0:
                if image_space is None or kp_mean > image_space[0]:
                    image_space = entry
                if early_exit and factor == BOX_EXPANSION and image_space[0] >= MIN_RTMPOSE_KP:
                    early_used = True
                    break
                continue
            if best_rotated is None:
                best_rotated = entry
            elif cls[0] == "THUMBS" and best_rotated[1][0] != "THUMBS":
                best_rotated = entry
            elif cls[0] != "THUMBS" and best_rotated[1][0] == "THUMBS":
                pass
            elif cls[0] == "THUMBS":
                if cls[1] > best_rotated[1][1] or (cls[1] == best_rotated[1][1]
                                                   and kp_mean > best_rotated[0]):
                    best_rotated = entry
            elif kp_mean > best_rotated[0]:
                best_rotated = entry
        if early_used:
            break
        if (factor == BOX_EXPANSION and image_space is not None
                and image_space[0] >= MIN_RTMPOSE_KP):
            break
    if image_space is not None and image_space[0] >= MIN_RTMPOSE_KP:
        winner, gated = image_space, False
    elif best_rotated is not None and best_rotated[0] >= MIN_RTMPOSE_KP:
        winner, gated = best_rotated, False
    elif best_kp is not None and best_kp[0] >= MIN_RTMPOSE_KP:
        winner, gated = best_kp, False
    elif image_space is not None:
        winner, gated = image_space, True
    elif best_kp is not None:
        winner, gated = best_kp, True
    else:
        return None, runs, reads
    kp_mean, cls, deg, factor = winner
    return {
        "rot": deg, "factor": factor,
        "gesture": f"{cls[0]}-{cls[1]}" if cls else "",
        "kp_mean": kp_mean, "gated": gated,
    }, runs, reads


def main():
    total_full = 0
    total_early = 0
    n_hands_full = 0
    n_hands_early = 0
    ident_ok = True
    winner_rot = Counter()
    needs_rotated = []
    early_triggered = 0
    n_boxes = 0

    files = sorted(SAMPLES.rglob("*"))
    files = [f for f in files if f.suffix.lower() in (".jpg", ".jpeg", ".png")]
    for f in files:
        img = decode_image(f)
        boxes = detect_boxes(img, det, pad_val=0, center_pad=False)
        boxes = [b for b in boxes if b[4] >= 0.25]
        for box in boxes[:2]:
            n_boxes += 1
            w_full, r_full, _ = search(img, box, early_exit=False)
            w_early, r_early, _ = search(img, box, early_exit=True)
            total_full += r_full
            total_early += r_early
            if w_full is not None:
                n_hands_full += 1
                winner_rot[w_full["rot"]] += 1
                if w_full["rot"] != 0:
                    needs_rotated.append((str(f.relative_to(SAMPLES)), w_full))
            if w_early is not None:
                n_hands_early += 1
            if w_full != w_early:
                ident_ok = False
                print(f"!! DIFF {f.name}: full={w_full} early={w_early}")
            if r_early < r_full:
                early_triggered += 1

    print(f"samples={len(files)} boxes={n_boxes}")
    print(f"RTMPose runs: FULL={total_full}  EARLY-EXIT={total_early}  "
          f"saved={total_full - total_early} "
          f"({100 * (total_full - total_early) / max(total_full, 1):.0f}%)")
    print(f"early-exit triggered on {early_triggered}/{n_boxes} boxes; "
          f"hands kept: full={n_hands_full} early={n_hands_early}; "
          f"identical winners: {ident_ok}")
    print(f"winner rotation distribution (full search): {dict(winner_rot)}")
    print(f"\nimages needing a ROTATED winner ({len(needs_rotated)}):")
    for name, w in needs_rotated:
        print(f"  {name}: {w['gesture']} at rot {w['rot']} ({w['factor']}x) "
              f"kp {w['kp_mean']:.2f}")
    print("\n-> the rotation search is still needed for those boxes (the edge "
          "fallback only covers the no-hands-found case); the early exit only "
          "skips provably-dead work.")


if __name__ == "__main__":
    sys.exit(main())
