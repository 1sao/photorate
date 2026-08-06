# LiteRT RTM hand models

Model recipe for running the app's two RTM hand models — RTMDet-nano hand
detector + RTMPose-m hand SimCC landmarker — as **LiteRT** models via the
CompiledModel API, on the GPU. Follows the project's `skills/` playbooks in
lifecycle order:

1. [`gpu-clean-conversion`](../../skills/gpu-clean-conversion/SKILL.md) — convert + host verify (this README)
2. [`on-device-verification`](../../skills/on-device-verification/SKILL.md) — device A/B, `LiteRtOnDeviceVerificationTest`
3. [`accuracy-safe-quantization`](../../skills/accuracy-safe-quantization/SKILL.md) — shrink after device verification passes
4. [`compiled-model-app-scaffolding`](../../skills/compiled-model-app-scaffolding/SKILL.md) — the `photosLiteRT` module

## Source models

The confirmed-source mmdeploy exports in `ml/original_models/` — the
`*_f32clean.onnx` GPU-ready variants (float32-only graphs, verified bit-exact
against the originals; see `ml/original_models/GPU_READY_README.md`):

| Model | Source ONNX | Input | Outputs |
|---|---|---|---|
| RTMDet-nano hand | `rtmdet_nano_8xb32-300e_hand-267f9c8f/end2end_f32clean.onnx` | `input` f32 [1,3,320,320] | `1442` boxes [1,2100,4], `1415` scores [1,2100,1] (raw anchors, NMS caller-side) |
| RTMPose-m hand SimCC | `rtmpose-m_simcc-hand5_pt-aic-coco_210e-256x256-74fb594_20230320/end2end_f32clean.onnx` | `input` f32 [1,3,256,256] | `simcc_x`/`simcc_y` [1,21,512] (raw SimCC, decode caller-side) |

`.onnx` was chosen over the `.pth` checkpoints: the exports are the verified,
bit-exact source of the app's current pipeline, while the `.pth` files would
require reconstructing the mmpose/mmdet architectures to export again.

## What was produced

```
ml/litert/
  converted/
    rtmdet_hand_320_f32.tflite     4.1 MB  float32, NCHW I/O preserved
    rtmdet_hand_320_f16.tflite     2.1 MB  fp16 variant (recommended; app not yet wired)
    rtmdet_hand_320_i8.tflite      1.2 MB  int8 variant (NOT accuracy-safe: 49/52 boxes)
    rtmpose_hand_256_f32.tflite   55.1 MB  float32, NCHW I/O preserved
    rtmpose_hand_256_f16.tflite   27.6 MB  fp16 variant (recommended; app not yet wired)
    rtmpose_hand_256_i8.tflite    14.1 MB  int8 variant (passes pose gate on GPU)
  refs/                            reference .npy dumps (ONNX ground truth)
  converted/
    README.md                      on-device verification + quantization report (the gate)
  scripts/
    rewrite_gpu_clean.py           ONNX rewrites (Gather/Split/HardSigmoid) + bit-exact check
    convert_models.py              onnx2tf *_f32clean_gpu.onnx -> float32 .tflite
    convert_originals.py           end2end.onnx -> *_end2end_f32.tflite (comparison)
    quantize_models.py             f32 -> fp16 + int8 (ai-edge-quantizer 0.8.0)
    dump_refs.py                   ONNX reference dumps (random + real sample)
    verify_host.py                 host parity (any variant path as CLI arg)
    probe_ops.py                   single-op GPU support probes
```

Quantization (accuracy-safe-quantization skill) came after the float32 on-device
verification passed. Sizes hit the predicted 1/2 (fp16) and 1/4 (int8); on-device
GPU results make **fp16 the recommended row** (both models pass every gate, ~2×
smaller, zero risk) and int8 a size/speed row for the pose only (the int8 detector
misses 3/52 boxes). The app still loads the float32 assets — wiring a quantized
asset into `LiteRtRtmModels` is a separate deployment step. Full numbers in
`converted/README.md`.

## App wiring

The `photosLiteRT` module implements the app's inference providers on top of
CompiledModel (see `compiled-model-app-scaffolding`):

- **Hand landmarks (default Android model):** `AndroidLiteRtHandLandmarkerFactory`
  — a port of the verified ONNX pipeline (RTMDet letterbox → multi-rotation
  RTMPose search → gesture gate) running the float32 conversions, GPU-first
  (OpenCL, FP32 precision) with CPU fallback. Wired as `LandmarkModel.LITERT`
  in `shared`'s PlatformModule.
- **Search:** `AndroidLiteRtAppClipSearchFactory` — the combined MobileCLIP-S1
  graph (`clip_s1_combined_f16.tflite`) in a CPU+GPU hybrid (the text head's
  CAST/EMBEDDING_LOOKUP have no GPU kernel; see `converted/CLIP_S1_README.md`),
  reusing `photosComponent`'s `ClipTokenizer`.

The ONNX provider (`photosOnnx`, hand pipeline + MobileCLIP-B search) is
unplugged from the app while LiteRT is the default.

On-device (Redmi M2003J15SC / Adreno 618 / OpenCL, 2026-08-06):
`LiteRtOnDeviceVerificationTest` (5/5, incl. strict GPU gates),
`LiteRtHandLandmarkDatasetTest` (1/1) and `LiteRtClipSearchDatasetTest` (2/2)
all pass. One documented finding vs the ONNX baseline: `2/2_coffee.jpg`'s
holding hand reads a confident THUMBS-4 on the GPU (keypoint drift flips the
gesture gates ONNX-CPU rejected) — tracked in the dataset test's
KNOWN_SCORE_MISMATCHES.

## Environment

`ml/litert/.venv` (Python 3.11): `onnx2tf 1.28.8`, `tensorflow 2.18.0`,
`onnxruntime 1.28.0`, `ai-edge-litert 2.1.6`, `numpy`, `opencv-python-headless`.
Conversion notes: `onnx2tf -k input` keeps the NCHW input layout; output names
are the generic `Identity`/`Identity_1` (flatc name-copying needs a Linux
binary; the device harness is index-based so names don't matter).

## Pipeline

```bash
ml/litert/.venv/bin/python ml/litert/scripts/rewrite_gpu_clean.py  # *_f32clean_gpu.onnx (bit-exact rewrites)
ml/litert/.venv/bin/python ml/litert/scripts/convert_models.py      # *_f32clean_gpu.onnx -> tflite
ml/litert/.venv/bin/python ml/litert/scripts/dump_refs.py           # npy ground truth
ml/litert/.venv/bin/python ml/litert/scripts/verify_host.py         # host parity + GPU toolkit
ml/litert/.venv/bin/python ml/litert/scripts/quantize_models.py     # fp16 + int8 variants
ml/litert/.venv/bin/python ml/litert/scripts/verify_host.py rtmdet_hand_320_f16.tflite rtmpose_hand_256_f16.tflite  # variant parity
```

The conversion step is the **GPU-clean** pipeline: the f32clean ONNX graphs are first
rewritten (`rewrite_gpu_clean.py`) so every node has a GPU kernel — constant-scalar
`Gather`s -> `Slice`+`Squeeze`, constant-size `Split`s -> `Slice`, and
`HardSigmoid` -> `Min(1, Max(0, a·x + b))` (onnx2tf lowers HardSigmoid to
`RELU_0_TO_1`, which has no GPU kernel). The rewrite is asserted bit-identical to the
source before conversion. The app manifest also declares `libvndksupport.so` /
`libOpenCL.so` as `<uses-native-library>` — without that the GPU delegate cannot dlopen
the vendor OpenCL runtime and fails at buffer creation.

## Verification results

Host (macOS arm64, ai-edge-litert CPU vs ONNX references, 2026-08-05):

| Model | Input | Output | corr | max abs diff |
|---|---|---|---|---|
| RTMDet | rand | boxes | 1.0 | 2.14e-4 |
| RTMDet | rand | scores | 1.0 | 4.25e-6 |
| RTMDet | real (5_kenya) | boxes | 1.0 | 2.40e-3 |
| RTMDet | real | scores | 1.0 | 1.33e-5 |
| RTMPose | rand | simcc_x/y | 1.0 | 1.7e-6 |
| RTMPose | real | simcc_x/y | 1.0 | 4.0e-6 |

Task gates on host: detector NMS box set matches the reference exactly
(38/38 rand, 52/52 real; max box diff 2.1e-4 / 1.5e-3); pose decoded
keypoints differ 0.0 px (conf diff ~1.5e-6).

**On-device (GPU): see `converted/README.md`** — the numbers there are the gate
for shipping. Summary (Pixel 9a, OpenCL, FP32 precision; latency = median of
per-run medians over multiple instrumented runs):

| Model | CPU | GPU | Speedup | GPU accuracy |
|---|---|---|---|---|
| RTMDet 320px | 31 ms | 25 ms | 1.2× | corr 1.0, NMS 52/52 boxes |
| RTMPose 256px | 154 ms | 35 ms | **4.4×** | corr 1.0, keypoints 0.0 px |

Both models get full strict-GPU residency and corr 1.0 on-device (FP32 precision;
default fp16 mode drifts ~1e-2 and can flip a marginal NMS box).

**Original `end2end.onnx` graphs** (in-graph NMS) were run through the same
pipeline to confirm the head-cut is required, not just convenient: the detector
converts but carries `NON_MAX_SUPPRESSION_V4`/`TOPK_V2`/data-dependent
`GATHER`s/`RELU_0_TO_1` (no GPU kernels — strict GPU compile fails on-device) and
onnx2tf caps its dynamic NMS output at 5 detections; the pose original does not
convert at all (dynamic batch dims break onnx2tf). Details and the per-model
numbers are in `converted/README.md`.
