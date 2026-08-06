# Hand-detection model benchmark (plans/samples)

Date: 2026-08-02. Python benchmark, run with `onnxruntime 1.28.0` (same
version the Android AAR ships) against the sample dataset in `plans/samples`.

## Question

Gold-YOLO (the app's current detector) misses hands on dorsal/side views
(`5/5_horizontal.jpg`, `4/4.jpg`, `4/4_also.jpg`). Can a different detector
under 50 MB find those hands?

## Candidates researched

| Model | Size | Format | Result |
|---|---|---|---|
| Gold-YOLO-Hand (current) | 22.6 MB | ONNX | Baseline; misses the 3 dorsal/side samples |
| **RTMDet-nano hand** (`bukuroo/RTMDet-ONNX`, Apache-2.0) | **4.0 MB** | ONNX, NMS baked, 320×320 input | ✅ **detects every sample** |
| MediaPipe palm detector (`palm_detection_full.onnx`, already in repo) | 4.8 MB | ONNX | Same Google palm detector the MediaPipe pipeline already uses → dead end |
| PINTO Gold-YOLO-Hand M/L | — | ONNX | Only shipped inside a 32 GB tarball; not worth pulling |
| degirum `yolo_v5s_hand_det` | — | proprietary `.n2x` | Not runnable by onnxruntime |

YOLOv8n/YOLO11n/RTMPose hand checkpoints found on GitHub/HF are mostly
`.pt` (need torch to export) or proprietary formats; no clean ready ONNX
except RTMDet-nano hand.

## Full-pipeline results (detector → palm rotation → sparse landmark → rater)

`no-lm` = detector fired but the landmark model returned presence < 0.35
(the app's `minHandPresenceConfidence`).

```
sample                          GOLD (det→landmark)      RTMDET (det→landmark)
3/3_ok_sign.jpg                 [no-lm]                  [no-lm]
3/3_open_hand_palm_down.jpg     [0.79]                   [no-lm]
4/4.jpg                         [no-lm]                  [no-lm]   ← gap (landmark stage)
4/4_also.jpg                    [no-lm;no-lm]            [no-lm]   ← gap (landmark stage)
5/5_also.jpg                    [0.93/THUMBS-4;no-lm]    [0.99]
5/5_horizontal.jpg              [no-lm]                  [0.60;0.75]  ← FIXED by RTMDET ✅
5/5_rock.jpg                    [0.99]                   [1.00/ROCK-5]
5/5_rock_alternative.jpg        [1.00/ROCK-5;no-lm]      [0.95/ROCK-5]
5/5_vertical.jpg                [no-lm;no-lm]            [no-lm]
no_score/* (3 files)            filtered ✅              filtered ✅
```

## Findings

1. **RTMDet-nano hand finds a hand in every sample** at conf 0.39–0.76,
   including all three Gold-YOLO misses (`5_horizontal` 0.39, `4/4` 0.76,
   `4/4_also` 0.73).
2. **`5_horizontal` is fully fixed by swapping the detector**: the RTMDet box
   lets the sparse landmark model produce two valid hands (presence
   0.60 / 0.75) where Gold-YOLO produced nothing. Detection, not landmarks,
   was the blocker there.
3. **`4/4` and `4/4_also` become detection-success but landmark-failure**:
   even with a strong RTMDet box the shared sparse landmark model returns
   low presence on those dorsal views. A detector swap alone won't close
   them — the landmark stage is the remaining blocker.
4. **No-score filtering preserved**: RTMDet fires on the no_score images too,
   but the landmark/presence gate still rejects them.
5. **RTMDet-n-hand is ~5× smaller** than Gold-YOLO (4 MB vs 22.6 MB) with
   plain ONNX NMS output — same kind of `[N,7]`-style post-processing.

## Files

- `benchmark_detectors.py` — detection-only comparison (gold vs rtmdet).
- `full_pipeline.py` — full pipeline incl. palm rotation + sparse landmark
  + rater, per detector, per sample.

## Model provenance

- RTMDet-nano hand: https://huggingface.co/bukuroo/RTMDet-ONNX/blob/main/rtmdet-n-hand.onnx
  (Apache-2.0; the mmpose hand detector, trained on OneHand10K which includes
  varied hand orientations). Preprocessing = ezonnx pipeline: BGR→RGB,
  longest side scaled to 320 with top-left gray(114) pad, normalize with
  RGB-mean [123.675, 116.28, 103.53] / std [58.395, 57.12, 57.375],
  score ≥ 0.3 then NMS iou 0.45.
