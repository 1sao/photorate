# GPU-ready variants (`*_f32clean.onnx`)

Added Aug 5 2026 for the NNAPI/GPU acceleration work (see
`docs/MODEL_ZOO_EXPLORATION.md` and the tooling in `plans/model_zoo_pinto/`).

## Why these exist

The confirmed-source `end2end.onnx` exports are not GPU-runnable on the
Tensor G4: RTMPose carries an int64 `Shape→Slice→Concat→Reshape` bookkeeping
chain and RTMDet has a baked NonMaxSuppression — the darwinn driver rejects
non-float32 operands and every NNAPI config fell back to CPU emulation,
slower than plain CPU.

These variants remove the int64 ops while staying **numerically identical**:

| File                                                                                    | What changed                                                                                                                                           | Verified parity                                                                                                                                          |
|-----------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `rtmdet_nano_8xb32-300e_hand-267f9c8f/end2end_f32clean.onnx`                            | onnxsim (frozen batch) + cut the redundant full-sort `TopK` tail; outputs raw anchors `boxes [1,2100,4]` + `scores [1,2100,1]` instead of baked `dets` | **0.0** vs original (weights bit-identical, all 123 Conv layers); 31/31 sample ratings identical with caller-side NMS (iou=0.6, score_thr=0.05, max 200) |
| `rtmpose-m_simcc-hand5_pt-aic-coco_210e-256x256-74fb594_20230320/end2end_f32clean.onnx` | onnxsim constant-folds the int64 shape bookkeeping (frozen `input 1,3,256,256`); same raw SimCC outputs `simcc_x/simcc_y [1,21,512]`                   | **2.7e-7** (float noise) vs original; 31/31 sample ratings identical                                                                                     |

Weights are the original OpenMMLab exports, unmodified — only dead graph
nodes were removed. Repro: `onnx/.venv/bin/python
plans/model_zoo_pinto/simplify_clean.py` (+ `surgery_cut_rtmdet_sort.py` for
the detector) and `bench_cleaned_vs_baseline.py` for the end-to-end check.

## App contract changes (caller-side post-processing)

- **Detector**: the app must now do NMS itself. The baked model's params
  (read from its initializers) are iou=0.6, score_thr=0.05, max_out=200.
  `AndroidOnnxHandLandmarker.detectBoxes()` implements this.
- **RTMPose**: unchanged contract (raw SimCC, decoded in
  `rtmposeLandmarks()`).

## FP16 (optional next step)

`rtmpose_hand_256_f32clean_fp16.onnx` (26.3 MB) / `rtmdet_320_f32clean_unsorted_fp16.onnx`
(1.9 MB) exist as experiment artifacts in `plans/model_zoo_pinto/`. They half
the sizes with ≤0.73 px keypoint / 0.67 px box shift, but should only be
packaged after an on-device NNAPI measurement confirms the GPU path is worth
the (tiny) accuracy cost.
