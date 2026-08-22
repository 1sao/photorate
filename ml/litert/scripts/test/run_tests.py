#!/usr/bin/env python3
"""Run regression tests. Directory names ARE the ground truth.

  confident_N  → must detect gesture with score N
  uncertain_N  → must detect hand (score N expected, but any score passes)
  rejected     → must NOT detect any gesture

Usage:
    python3 ml/litert/scripts/test/run_tests.py
    python3 ml/litert/scripts/test/run_tests.py --bucket confident_5
    python3 ml/litert/scripts/test/run_tests.py --verbose
"""
import argparse
import re
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent.parent.parent.parent
sys.path.insert(0, str(PROJECT_ROOT / "plans" / "benchmarks"))
from rtmpose_only_v6 import prepare_image, detect_hands, run_pipeline

IMAGES_DIR = SCRIPT_DIR / "images"


def parse_bucket(name: str) -> dict:
    """Parse directory name into test expectations."""
    if name == "rejected":
        return {"expect": "filter"}
    m = re.match(r"^(confident|uncertain)_(\d+)$", name)
    if m:
        level, score = m.group(1), int(m.group(2))
        return {
            "expect": "detect" if level == "confident" else "uncertain",
            "score": score,
        }
    return {"expect": "unknown"}


class TestResult:
    def __init__(self, bucket: str, image: str, passed: bool, detail: str = ""):
        self.bucket = bucket
        self.image = image
        self.passed = passed
        self.detail = detail


def check_image(path: Path, bucket_config: dict) -> TestResult:
    """Run pipeline on one image and check against expectations."""
    img_name = path.name
    expect = bucket_config["expect"]

    img = prepare_image(path)
    if img is None:
        if expect == "filter":
            return TestResult("", img_name, True, "decode_failed (ok)")
        return TestResult("", img_name, False, "decode_failed")

    boxes = detect_hands(img)
    results = run_pipeline(img, boxes)

    has_gesture = any(r.gesture is not None for r in results)
    has_hand = any(r.kp_mean >= 0.30 for r in results)
    detected = [(r.gesture, r.score, r.kp_mean, r.uncertain)
                for r in results if r.gesture]

    if expect == "filter":
        if not has_gesture:
            return TestResult("", img_name, True)
        labels = [f"{g}-{s}" for g, s, _, _ in detected]
        return TestResult("", img_name, False, f"detected: {labels}")

    if expect == "detect":
        expected_score = bucket_config["score"]
        if not has_gesture:
            return TestResult("", img_name, False, "no gesture detected")
        for gesture, score, kp, _ in detected:
            if score == expected_score:
                return TestResult("", img_name, True,
                                  f"{gesture}-{score} (kp={kp:.2f})")
        labels = [f"{g}-{s}" for g, s, _, _ in detected]
        return TestResult("", img_name, False,
                          f"expected score {expected_score}, got {labels}")

    if expect == "uncertain":
        expected_score = bucket_config.get("score")
        if not has_hand:
            return TestResult("", img_name, False, "no hand detected")
        if not has_gesture:
            return TestResult("", img_name, False,
                              f"detected but no gesture (expected score {expected_score})")
        for gesture, score, kp, _ in detected:
            if score == expected_score:
                return TestResult("", img_name, True,
                                  f"{gesture}-{score} (kp={kp:.2f})")
        labels = [f"{g}-{s}" for g, s, _, _ in detected]
        return TestResult("", img_name, False,
                          f"expected score {expected_score}, got {labels}")

    return TestResult("", img_name, False, f"unknown expect: {expect}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bucket", help="Test only this bucket")
    parser.add_argument("--verbose", "-v", action="store_true")
    args = parser.parse_args()

    all_results = []

    for bucket_dir in sorted(IMAGES_DIR.iterdir()):
        if not bucket_dir.is_dir():
            continue
        if args.bucket and bucket_dir.name != args.bucket:
            continue

        config = parse_bucket(bucket_dir.name)
        if config["expect"] == "unknown":
            continue

        images = sorted(
            p for p in bucket_dir.iterdir()
            if p.is_file() and p.suffix.lower() in (".jpg", ".jpeg", ".png")
        )
        if not images:
            continue

        bucket_results = []
        for img_path in images:
            result = check_image(img_path, config)
            result.bucket = bucket_dir.name
            bucket_results.append(result)

        passed = sum(1 for r in bucket_results if r.passed)
        total = len(bucket_results)
        status = "✓" if passed == total else "✗"

        label = bucket_dir.name
        if config["expect"] == "detect":
            label += f" (must detect score={config['score']})"
        elif config["expect"] == "uncertain":
            label += f" (must detect score={config['score']})"
        else:
            label += " (must filter)"

        print(f"\n{status} {label} [{passed}/{total}]")

        if args.verbose or passed < total:
            for r in bucket_results:
                mark = "✓" if r.passed else "✗"
                detail = f" ({r.detail})" if r.detail else ""
                print(f"    {mark} {r.image}{detail}")

        all_results.extend(bucket_results)

    total = len(all_results)
    passed = sum(1 for r in all_results if r.passed)
    failed = total - passed
    print(f"\n{'=' * 60}")
    print(f"  {passed}/{total} passed, {failed} failed")
    if failed > 0:
        print(f"\n  Failed:")
        for r in all_results:
            if not r.passed:
                print(f"    {r.bucket}/{r.image}: {r.detail}")
    print(f"{'=' * 60}")

    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
