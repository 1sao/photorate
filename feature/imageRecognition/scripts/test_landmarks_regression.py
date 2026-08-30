#!/usr/bin/env python3
"""Landmark regression test runner — Python counterpart of LiteRtHandLandmarkerTest.

Kotlin counterpart (KDoc links both ways):
- app/src/androidTest/kotlin/isao/photorate/android/LiteRtHandLandmarkerTest.kt
- app/src/androidTest/kotlin/isao/photorate/android/LandmarkerTest.kt

Runs the pipeline (ONNX/rtmlib oracle or TFLite device-faithful backend) over
the bucket dataset in app/src/androidTest/assets/ and asserts results match
expectations. Expectation logic is identical to LandmarkerTest.allSamplesScoreAsExpected:

- confident_N -> must detect gesture with score N AND confidence > 0
- uncertain_N -> must detect hand with gesture score N AND confidence == 0
- rejected    -> must NOT detect any gesture

Backends:
- onnx  — rtmlib package on local ONNX models (ground-truth baseline)
- tflite — ai-edge-litert on converted tflite models (device-faithful)

Usage:
    python3 test_landmarks_regression.py --backend onnx
    python3 test_landmarks_regression.py --backend tflite
    python3 test_landmarks_regression.py --backend onnx --bucket confident_5
    python3 test_landmarks_regression.py --backend onnx --json results.json
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS_DIR))

from rtm_pipeline import Pipeline
from gesture_classify import create_recognizers
from dataset import collect_buckets


def run_single_image(pipe, recognizers, image_path):
    """Run pipeline on one image, return result dict."""
    from rtm_pipeline import decode_image

    img = decode_image(image_path)
    if img is None:
        return {"file": image_path.name, "error": "decode_failed", "hands": []}

    hands = pipe.detect_with_recognizers(img, recognizers)
    return {
        "file": image_path.name,
        "hands": [
            {
                "gesture": h["gesture"],
                "score": h["score"],
                "confidence": round(h["confidence"], 4),
                "kpMean": h["kpMean"],
                "detScore": h["detScore"],
                "rotation": h["rotation"],
            }
            for h in hands
        ],
    }


def check_expectation(config, result):
    """Check result against bucket expectation (mirrors LandmarkerTest logic)."""
    expect = config.expect
    hands = result.get("hands", [])
    scores = [h["score"] for h in hands if h["gesture"] is not None]
    confidences = [h["confidence"] for h in hands if h["gesture"] is not None]
    has_gesture = len(scores) > 0

    if result.get("error"):
        if expect == "filter":
            return True, "decode_failed (ok for filter)"
        return False, "decode_failed"

    if expect == "filter":
        if not has_gesture:
            return True, ""
        return False, f"expected filter, got scores={scores}"

    if expect == "detect":
        expected_score = config.score
        if not has_gesture:
            return False, f"expected score {expected_score} with non-zero confidence, got no gesture"
        for h in hands:
            if h["gesture"] and h["score"] == expected_score and h["confidence"] > 0:
                return True, ""
        return False, f"expected score {expected_score} with non-zero confidence, got {[(h['gesture'], h['score'], h['confidence']) for h in hands]}"

    if expect == "uncertain":
        expected_score = config.score
        if not has_gesture:
            return False, f"expected score {expected_score} with zero confidence, got no gesture"
        for h in hands:
            if h["gesture"] and h["score"] == expected_score and h["confidence"] == 0:
                return True, ""
        return False, f"expected score {expected_score} with zero confidence, got {[(h['gesture'], h['score'], h['confidence']) for h in hands]}"

    return False, f"unknown expectation: {expect}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend", choices=["onnx", "tflite"], default="onnx")
    parser.add_argument("--bucket", help="Test only this bucket")
    parser.add_argument("--json", dest="json_out", help="Write results to JSON file")
    parser.add_argument("--verbose", "-v", action="store_true")
    args = parser.parse_args()

    print(f"Backend: {args.backend}")
    print(f"Loading pipeline...")
    pipe = Pipeline(backend=args.backend)
    recognizers = create_recognizers()
    print(f"Recognizers: {[type(r).__name__ for r in recognizers]}")
    print()

    all_buckets = collect_buckets()
    if args.bucket:
        all_buckets = [(c, imgs) for c, imgs in all_buckets if c.name == args.bucket]

    if not all_buckets:
        print("No buckets found.")
        sys.exit(1)

    all_results = []
    total_passed = 0
    total_failed = 0

    for config, images in all_buckets:
        passed = 0
        failed = 0
        bucket_results = []

        label = f"{config.name} (expect={config.expect}"
        if config.expect != "filter":
            label += f", score={config.score}"
        label += ")"

        print(f"\n--- {label} [{len(images)} images] ---")

        for img_path in images:
            result = run_single_image(pipe, recognizers, img_path)
            ok, detail = check_expectation(config, result)
            result["bucket"] = config.name
            result["passed"] = ok
            result["detail"] = detail
            bucket_results.append(result)

            mark = "✓" if ok else "✗"
            status = detail if detail else "OK"
            if args.verbose or not ok:
                print(f"  {mark} {result['file']}: {status}")

            if ok:
                passed += 1
            else:
                failed += 1

        total_passed += passed
        total_failed += failed
        all_results.extend(bucket_results)

        print(f"  => {passed}/{passed + failed} passed")

    print(f"\n{'=' * 60}")
    print(f"  Backend: {args.backend}")
    print(f"  Total: {total_passed}/{total_passed + total_failed} passed, {total_failed} failed")
    print(f"{'=' * 60}")

    if total_failed > 0:
        print(f"\n  Failed images:")
        for r in all_results:
            if not r["passed"]:
                print(f"    {r['bucket']}/{r['file']}: {r['detail']}")

    if args.json_out:
        out_path = Path(args.json_out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump(all_results, f, ensure_ascii=False, indent=2)
        print(f"\n  Results written to {out_path}")

    return 0 if total_failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
