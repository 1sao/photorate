#!/usr/bin/env python3
"""Regression test: runs the rtmpose_only_v5 pipeline on both plans/samples
and on_device_test images, checking expected results.

Usage: source onnx/.venv/bin/activate && python3 on_device_test/test_pipeline.py
"""
import sys
from pathlib import Path

# Import the pipeline from rtmpose_only_v5
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "plans" / "benchmarks"))
from rtmpose_only_v5 import decode_640, rtmdet_boxes, run_pipeline

ON_DEVICE = Path(__file__).resolve().parent / "images"
SAMPLES = Path(__file__).resolve().parent.parent / "plans" / "samples"

# Expected results for on_device_test images:
#   "pass" = hand detected with any recognized gesture (THUMBS/ROCK/OK_SIGN)
#   "filter" = no hand detected / filtered out (no recognized gesture)
#   (score) = must detect with this specific score
EXPECTED = {
    # confident_kept: all should be detected
    "confident_kept": {
        "200_": "pass",
        "228_": "pass",
        "26_": "pass",
        "3448_": "pass",
        "3469_": "pass",
        "3530_": "pass",
        "3531_": "pass",
        "3532_": "pass",
        "3553_": "pass",
        "3574_": "pass",
        "3589_": "pass",
        "3598_": "pass",
        "49_": "pass",
        "56_": "pass",
    },
    # uncertain_kept: user says these are still invalid, filtering is OK
    "uncertain_kept": {
        "130_": "filter",
        "140_": "filter",
        "146_": "filter",
        "181_": 1,
        "189_": "filter",
        "190_": "filter",
        "193_": "filter",
        "212_": "filter",
        "248_": "filter",
        "249_": "filter",
        "3533_": "filter",
    },
    # uncertain_rejected: must be filtered out
    "uncertain_rejected": {
        "22_": "filter",
        "33_": "filter",
        "34_": "filter",
        "46_": "filter",
        "61_": "filter",
        "83_": "filter",
        "91_": "filter",
        "118_": "filter",
        "119_": "filter",
        "131_": "filter",
        "174_": "filter",
        "175_": "filter",
        "198_": "filter",
        "215_": "filter",
        "267_": "filter",
        "271_": "filter",
        "278_": "filter",
        "279_": "filter",
        "3346_": "filter",
        "3452_": "filter",
    },
    # user_corrections: specific expected scores from user
    "user_corrections": {
        "42_": 5,  # user confirmed 5
        "66_": 4,  # user downgraded to 4
        "147_": 2,  # user major downgrade to 2
        "148_": 3,  # user confirmed 3
        "3484_": 4,  # user downgraded to 4
    },
}


def test_on_device():
    results = {"pass": [], "fail_filter": [], "fail_score": []}
    total = 0
    for cat, expectations in EXPECTED.items():
        cat_dir = ON_DEVICE / cat
        if not cat_dir.exists():
            continue
        for f in sorted(cat_dir.glob("*.jpg")):
            media_id = f.name.split("_")[0]
            # Find matching expectation
            expected = None
            for prefix, exp in expectations.items():
                if f.name.startswith(prefix):
                    expected = exp
                    break
            if expected is None:
                continue

            total += 1
            img = decode_640(f)
            if img is None:
                if expected == "filter":
                    results["pass"].append(f"{cat}/{f.name}")
                else:
                    results["fail_filter"].append(f"{cat}/{f.name} (decode failed)")
                continue

            boxes = rtmdet_boxes(img)
            pipeline_results = run_pipeline(img, boxes, use_palm=True)
            has_gesture = any(
                r[1] is not None and r[1]["cls"] is not None
                for r in pipeline_results
            )
            detected_scores = [
                r[1]["cls"][1] for r in pipeline_results
                if r[1] is not None and r[1]["cls"] is not None
            ]

            if expected == "filter":
                if not has_gesture:
                    results["pass"].append(f"{cat}/{f.name}")
                else:
                    results["fail_filter"].append(
                        f"{cat}/{f.name} (detected: {detected_scores})"
                    )
            elif expected == "pass":
                if has_gesture:
                    results["pass"].append(f"{cat}/{f.name}")
                else:
                    results["fail_filter"].append(f"{cat}/{f.name} (no gesture)")
            else:
                # Specific score expected
                if expected in detected_scores:
                    results["pass"].append(f"{cat}/{f.name}")
                elif has_gesture:
                    results["fail_score"].append(
                        f"{cat}/{f.name} (expected {expected}, got {detected_scores})"
                    )
                else:
                    results["fail_filter"].append(
                        f"{cat}/{f.name} (expected {expected}, no gesture)"
                    )

    print(f"\nOn-device test results ({total} images):")
    print(f"  PASS:  {len(results['pass'])}")
    print(f"  FAIL (should filter): {len(results['fail_filter'])}")
    for f in results["fail_filter"]:
        print(f"    {f}")
    print(f"  FAIL (wrong score):   {len(results['fail_score'])}")
    for f in results["fail_score"]:
        print(f"    {f}")
    return len(results["fail_filter"]) == 0 and len(results["fail_score"]) == 0


def test_samples():
    """Run the existing plans/samples benchmark and check results."""
    correct = 0
    total_scored = 0
    false_positives = []
    misses = []
    for f in sorted(SAMPLES.rglob("*.jpg")):
        rel = str(f.relative_to(SAMPLES))
        score_dir = rel.split("/")[0]
        if score_dir == "search":
            continue
        img = decode_640(f)
        boxes = rtmdet_boxes(img)
        results = run_pipeline(img, boxes, use_palm=True)
        has_gesture = any(
            r[1] is not None and r[1]["cls"] is not None for r in results
        )
        if score_dir == "no_score":
            if has_gesture:
                false_positives.append(rel)
        else:
            total_scored += 1
            expected = int(score_dir)
            ok = any(
                r[1] is not None and r[1]["cls"] is not None and r[1]["cls"][1] == expected
                for r in results
            )
            if ok:
                correct += 1
            else:
                misses.append(rel)

    print(f"\nPlans/samples results:")
    print(f"  Accuracy: {correct}/{total_scored}")
    if misses:
        print(f"  Misses: {misses}")
    if false_positives:
        print(f"  False positives: {false_positives}")
    return correct == total_scored and len(false_positives) == 0


if __name__ == "__main__":
    samples_ok = test_samples()
    device_ok = test_on_device()
    if samples_ok and device_ok:
        print("\n✓ ALL TESTS PASSED")
        sys.exit(0)
    else:
        print("\n✗ SOME TESTS FAILED")
        sys.exit(1)
