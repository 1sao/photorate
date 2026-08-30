# Image Recognition — Python Regression Scripts

Host-side Python harness for the hand-landmark (RTMDet + RTMPose) and CLIP-search
pipelines. Serves as the ground-truth baseline for the Kotlin/device implementations.

## Setup

```bash
cd feature/imageRecognition/scripts
python3.11 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/pip install onnx   # only needed to (re)generate the raw-SimCC model
.venv/bin/python strip_simcc_postprocess.py
```

## Scripts

| Script                           | Purpose                                                                                                                                                                                                     | Kotlin counterpart                                                         |
|----------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------|
| `rtm_pipeline.py`                | Shared RTMDet+RTMPose pipeline with two backends: `onnx` (rtmlib on `onnx/models/*.onnx`, ground-truth oracle) and `tflite` (ai-edge-litert on `ml/litert/converted/*_f32.tflite`, device-faithful).        | `LiteRtHandLandmarker.kt`                                                  |
| `gesture_classify.py`            | 1:1 port of `HandFeatures2` + the 4 recognizers (ThumbSignal, OkSign, ThumbOnly, LenientThumb) in provider order.                                                                                           | `HandFeatures2.kt`, `*Recognizer.kt`, `LiteRtGestureRecognizerProvider.kt` |
| `dataset.py`                     | Bucket parsing (`confident_N`/`uncertain_N`/`rejected`) reading `app/src/androidTest/assets/` directly.                                                                                                     | `LandmarkerTest.parseBucket`                                               |
| `test_landmarks_regression.py`   | Bucket regression runner with expectations identical to the Kotlin test.                                                                                                                                    | `LiteRtHandLandmarkerTest` / `LandmarkerTest`                              |
| `dump_landmarks.py`              | Per-image JSON dump + optional skeleton overlay for debugging a failing image.                                                                                                                              | —                                                                          |
| `test_clip_search_regression.py` | Host mirror of the CLIP search test: top-1 ranking + scaled-decode equivalence.                                                                                                                             | `LiteRtClipSearchDatasetTest`                                              |
| `compare_device_log.py`          | Diffs logcat from the Kotlin test against the Python JSON to localize failures (conversion vs pipeline vs classifier).                                                                                      | `LandmarkerTest.log`                                                       |
| `strip_simcc_postprocess.py`     | Regenerates `onnx/models/rtmpose_hand_rawsimcc.onnx` by stripping the PINTO baked SimCC decode (whose `bboxes_width_height` rescale convention differs from rtmlib's). Required by the ONNX oracle backend. | —                                                                          |

## Run

```bash
# Landmark regression (oracle baseline)
.venv/bin/python test_landmarks_regression.py --backend onnx --json output/results_onnx.json

# Landmark regression (device-faithful)
.venv/bin/python test_landmarks_regression.py --backend tflite --json output/results_tflite.json

# Single image debug with overlay
.venv/bin/python dump_landmarks.py ../../../app/src/androidTest/assets/confident_5/5_kimbo.jpg --backend onnx --overlay

# CLIP search regression
.venv/bin/python test_clip_search_regression.py

# Diff device logcat vs Python results
adb logcat -d -s LiteRtHandLandmarkerTest | .venv/bin/python compare_device_log.py --json output/results_tflite.json
```

## Verified baseline (latest run)

| Backend                    | Result                      | JSON                         |
|----------------------------|-----------------------------|------------------------------|
| `tflite` (device-faithful) | **55/62 passed** (7 failed) | `output/results_tflite.json` |
| `onnx` (rtmlib oracle)     | **54/62 passed** (8 failed) | `output/results_onnx.json`   |

The tflite backend reproduces the known on-device failures from
`LITERT_INVESTIGATION_SUMMARY.md` (7/8 on host):

- `confident_3/PXL_20260429_051734137.MP.jpg`: no gesture (Python/rtmlib finds rotated candidate)
- `confident_5/IMG_20250209_221817.jpg`: no gesture
- `confident_5/5_kimbo.jpg`: uncertain (confidence 0) instead of confident
- `uncertain_1/181_PXL_20260329_171427874.MP.jpg`: confidence 1.0 instead of 0
- `uncertain_5/42_PXL_20260621_161610440.MP.jpg`: confidence 1.0 instead of 0

The onnx oracle adds one extra divergence the device filters away:
`rejected/271_PXL_20260126_193942415.jpg` scores 5 on the raw rtmlib path
(where the device's fixed-crop/rotation-0 strategy filters it) — this is an
oracle-vs-device difference to investigate, not a harness bug.

Diffs between `results_onnx.json` and `results_tflite.json` localize each
failure to the LiteRT conversion or the fixed-crop/rotation-0 strategy.

## ONNX oracle notes

The oracle drives both ONNX sessions directly (not via rtmlib's tool classes):
its `RTMDet` wrapper converts to RGB — which starves the PINTO hand-detector
export, which expects BGR — and its `RTMPose.__call__` cannot forward the
`bboxes_width_height` input this export requires. Preprocessing math is mirrored
1:1 from the installed rtmlib source (`bbox_xyxy2cs(1.25)`, `get_warp_matrix`,
`get_simcc_maximum`), and the pose graph is the raw-SimCC export generated by
`strip_simcc_postprocess.py` so decoding uses rtmlib's own postprocess.
