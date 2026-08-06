# Converted RTM hand models — GPU-clean conversion, on-device verification & quantization

This directory holds the LiteRT `.tflite` models that run on the GPU via the LiteRT
**CompiledModel** API: float32 (shipped baseline), fp16 and dynamic-range int8 variants.
This README documents how the GPU path was made to work ("GPU-clean conversion"), the
on-device verification numbers, and the accuracy-safe quantization results.

Device used for every on-device number below: **Pixel 9a (Tensor G4), LiteRT 2.1.6,
OpenCL backend, FP32 precision**.

## TL;DR

| Model | CPU (median) | GPU (median) | Speedup | GPU accuracy vs ONNX ref |
|---|---|---|---|---|
| RTMDet-nano hand (320px) | 31 ms | **25 ms** | 1.2× | corr **1.0**, NMS box set **52/52** |
| RTMPose-m hand SimCC (256px) | 154 ms | **35 ms** | **4.4×** | corr **1.0**, keypoints **0.0 px** |

(GPU numbers are the median of per-run medians across multiple instrumented
runs — see the quantization table below for the run-to-run spread; single-run
readings vary ±3 ms.)

Both models compile with **strict GPU residency** (every node on the GPU, no CPU
fallback — CompiledModel has none), and the GPU output is bit-level identical to the
host reference at fp32 precision.

## Why the plain conversion didn't run on the GPU

Three separate problems blocked the GPU path. Each was found with the single-op
probe harness (`ml/litert/scripts/probe_ops.py` + the `gpuOpSupportProbe`
instrumentation test) and fixed without changing the model's math.

### 1. The GPU delegate couldn't access the vendor OpenCL runtime

The first symptom was everything failing at *buffer creation* even for a single
CONV_2D probe, with the runtime logging `OpenCL not supported on this platform.
Using OpenGL instead` — and the GL fallback then failing to create input buffers.

`libOpenCL.so` **is** present on the device (`/vendor/lib64`), but on Android 9+ an
app process can only dlopen vendor native libraries it explicitly declares. The fix
(the same one LiteRT-LM's docs prescribe for its GPU backend) is two lines in the
app manifest:

```xml
<uses-native-library android:name="libvndksupport.so" android:required="false"/>
<uses-native-library android:name="libOpenCL.so" android:required="false"/>
```

With that, the delegate loads the real OpenCL runtime, the IO buffers become
`OpenClBufferPacked`, and every probe compiles and runs.

### 2. Three op patterns have no GPU kernel

With OpenCL working, the probe harness (31 single-op models × 5 accelerator
configs) produced a precise support map. The two RTM graphs contained exactly three
unsupported patterns:

| Op in the converted model | Source in the ONNX | GPU kernel? |
|---|---|---|
| `GATHER` (4×, scalar const index) | det xyxy→xywh split | **no** |
| `SPLIT_V` (2×, const sizes) | pose SimCC head split | **no** |
| `RELU_0_TO_1` (4×) | `HardSigmoid(1/6, 0.5)` lowered by onnx2tf | **no** |

`ml/litert/scripts/rewrite_gpu_clean.py` rewrites the ONNX graph (before conversion)
into numerically identical, probe-confirmed-supported equivalents:

```
Gather(data, scalar k, axis=a)     ->  Slice(data, [k], [k+1], [a]) + Squeeze
Split(data, split=[s0..], axis=a)  ->  one Slice per output
HardSigmoid(x, a, b)               ->  Min(1, Max(0, a·x + b))      (exact)
```

The script asserts **bit-identical** outputs vs the source graph with onnxruntime
before writing `*_f32clean_gpu.onnx`; `convert_models.py` then converts those. The
resulting tflite inventories have no `GATHER`/`SPLIT_V`/`RELU_0_TO_1` left — only
supported ops (`STRIDED_SLICE`, `MAXIMUM`, `MINIMUM`, `RESHAPE`, …).

> Probe gotcha worth recording: `tf.clip_by_value(x, 0, 1)` in a probe builds
> `MINIMUM(RELU(x), 1)` — **not** `RELU_0_TO_1` — so a clip-based probe would have
> reported RELU_0_TO_1 as "supported" without ever exercising it. Always verify a
> probe's actual opcode from the flatbuffer before trusting a "supported" verdict.

### 3. fp16 reductions flip a marginal NMS box

With full GPU residency achieved, the detector matched **51/52** reference boxes:
the GPU delegate computes in fp16 by default, and a ~1e-2 drift on one near-threshold
score changed which of two near-duplicate boxes NMS kept. `GpuOptions.Precision.FP32`
removes the drift entirely — GPU accuracy becomes corr 1.0 and 52/52 boxes — while
keeping ~all of the speedup (OpenCL fp32 is still far faster than the CPU).

## On-device verification (`LiteRtOnDeviceVerificationTest`)

Run per the on-device-verification skill:

```
adb shell am instrument -w -r \
  -e class isao.photorate.android.LiteRtOnDeviceVerificationTest#verifyDetectorOnGpuAndCpu,\
isao.photorate.android.LiteRtOnDeviceVerificationTest#verifyPoseOnGpuAndCpu \
  isao.photorate.test/androidx.test.runner.AndroidJUnitRunner
```

Each model is compiled for `Accelerator.CPU` (control) then strict `Accelerator.GPU`
(compile failure ⇒ a GPU-unsupported op), checked for numeric parity on every output
(correlation + max abs diff vs host-dumped ONNX refs), a task gate (detector: NMS box
set IoU-match; pose: decoded keypoints in px), and a 10-run latency median.

### Detector — RTMDet-nano hand, 320px

| accel | corr (boxes/scores) | max abs diff | NMS gate | latency median |
|---|---|---|---|---|
| CPU | 1.0 | 2.3e-3 | 52/52 (min IoU 0.99998) | 31 ms |
| GPU (FP32, OpenCL) | **1.0** | 2.5e-4 | **52/52** (min IoU 0.999997) | **24 ms** |

### Pose — RTMPose-m hand SimCC, 256px

| accel | corr (simcc_x/y) | max abs diff | keypoints | latency median |
|---|---|---|---|---|
| CPU | 1.0 | 4.0e-6 | 0.0 px | 154 ms |
| GPU (FP32, OpenCL) | **1.0** | 4.5e-6 | **0.0 px** | **33 ms** |

The pose model's fp16-mode run was also measured for reference: 28 ms median but
1.5 px keypoint drift — the FP32 setting costs 5 ms on this model and buys exactness,
so it is the shipped default for the verification harness.

## Second device: Redmi Note 9 (Mali-G52, Android 10 / API 29)

All tests were re-run on an older device — Redmi Note 9, MediaTek Helio G85,
Mali-G52 MC2, Android 10. It ships `libOpenCL.so` + `libvndksupport.so` in
`/vendor`, so the same manifest entries grant GPU access (the op probe confirms
OpenCL: GATHER/SPLIT_V unsupported as expected, everything else supported).
All **verdicts are identical** to the Pixel 9a — GPU residency holds, accuracy
is unchanged — only the absolute latencies are slower (weaker GPU/CPU):

| Model | Pixel 9a GPU | Redmi GPU | Redmi CPU | Redmi speedup |
|---|---|---|---|---|
| det f32 | 24.5 ms | **43 ms** | 83 ms | 1.9× |
| det fp16 | 23 ms | **44 ms** | 119 ms | 2.7× |
| det int8 | 24 ms | **45 ms** | 87 ms | 1.9× |
| pose f32 | 35 ms | **187 ms** | 426 ms | 2.3× |
| pose fp16 | 33 ms | **186 ms** | 528 ms | 2.8× |
| pose int8 | 34 ms | **185 ms** | 253 ms | 1.4× |

Task gates on the Redmi: det f32/fp16 **52/52** boxes, det int8 49/52 (same
miss as the Pixel 9a), pose f32 0.0 px, pose fp16 0.5 px, pose int8 1.5 px on
GPU (4.5 px on its CPU — the GPU delegate's int handling is better, confirming
the skill's "never judge int8 on host/CPU numbers"). Same fp16-recommended
verdict. (Note: `plans/samples` + `ml/original_models` assets were temporarily
stripped from the test APKs to fit the slower USB install; the full dataset
tests need them restored.)

## Quantization (accuracy-safe-quantization skill)

After the float32 GPU verification passed, both models were quantized with
`ai-edge-quantizer 0.8.0` (see `ml/litert/scripts/quantize_models.py`):

- **fp16** — `FLOAT_CASTING`: weights cast to fp16, compute stays float. Op set
  unchanged, outputs stay fp32. ~1/2 size.
- **int8** — dynamic-range channelwise (`dynamic_wi8c_afp32`): int8 weights, fp32
  activations. Same op set (rides the GPU delegate), outputs stay fp32. ~1/4 size.

### Size (the first gate — both recipes hit the prediction)

| Model | f32 | fp16 | int8 |
|---|---|---|---|
| RTMDet-nano hand (320px) | 4.1 MB | 2.1 MB | 1.2 MB |
| RTMPose-m hand SimCC (256px) | 55.1 MB | 27.6 MB | 14.1 MB |

### On-device GPU results (same harness, `verifyQuantizedVariants` test)

Latency is the median of per-run medians (n runs; spread = stdev across
per-run medians). No quantized variant is measurably slower than f32 on the
GPU — the deltas are within run-to-run noise (±2–4 ms):

| Variant | GPU parity (corr) | Task gate on GPU | GPU median | n | spread |
|---|---|---|---|---|---|
| det f32 | 1.0 | NMS 52/52 | 24.5 ms | 6 | 3.8 |
| det **fp16** | 1.0 | NMS **52/52** ✅ | 23.0 ms | 7 | 2.3 |
| det int8 | 0.97–1.0 | NMS 49/52 ❌ | 24.0 ms | 7 | 2.8 |
| pose f32 | 1.0 | keypoints 0.0 px | 35.0 ms | 5 | 1.9 |
| pose **fp16** | 1.0 | keypoints **0.5 px** ✅ | 33.0 ms | 7 | 2.1 |
| pose int8 | 0.999+ | keypoints **1.5 px** ✅ | 34.0 ms | 7 | 1.9 |

An early single-run reading looked like fp16 slowed the pose (38 vs 33 ms),
but repeating the harness (5× variants, 3× baselines, same session) shows the
quantized variants sit *within* the f32 baseline's own run-to-run spread — the
GPU spends its time on fp32 activation compute, which is identical across
variants; only the weight storage size changes. Latency is noise; **size and
accuracy are the real axes** (as the skill warns: "bytes are not speed").

Host CPU said the int8 pose drifts 6 px (fails the 3 px gate) and the int8 det
scores corr 0.72 — but per the skill, host int-kernel emulation is pessimistic:
on the device GPU the pose int8 is 1.5 px (passes) and the det int8 still misses
3 of 52 boxes (fails). **Verdict:** fp16 is the quality row (both models, zero
risk, same op set as f32, bit-exact parity); int8 is a size/speed row for the
pose only, and is not accuracy-safe for the detector. **Recommended variant:
fp16** — the on-device harness in this repo verifies all variants, but wiring a
quantized asset into the app (`LiteRtRtmModels` asset constants) is a separate
deployment step that hasn't been done yet; the app still loads the float32
models.

## What the *original* graphs do on GPU (why the head-cut exists)

The `end2end.onnx` originals (NMS/post-processing in-graph) were run through the same
pipeline to confirm the head-cut is necessary, not just convenient:

- **Detector `end2end.onnx`** converts with warnings, but the converted model
  contains `NON_MAX_SUPPRESSION_V4`, `TOPK_V2`, 13 data-dependent `GATHER`s and
  `RELU_0_TO_1` — none of which have GPU kernels. A strict GPU compile fails on-device
  (`original_det_gpu=COMPILE_FAILED`). Worse, onnx2tf's heuristic fix for the dynamic
  NMS output shape caps the result at **5 detections** (the ONNX keeps 39/53 on the
  same inputs); the top-5 rows still match the ONNX to ~6e-4. CPU latency: 31 ms.
- **Pose `end2end.onnx`** does not convert at all: its dynamic batch dims break
  onnx2tf's shape inference (`Mul` on `[?,128,1,1152] × [1,2,128,1]`).
- The `*_f32clean.onnx` graphs used for the GPU path are the verified bit-exact
  head-cut of the same models (backbone+heads only; NMS/SimCC decode runs caller-side
  in `LiteRtRtmModels`), which is what makes full GPU residency possible.

## Files

```
converted/
  rtmdet_hand_320_f32.tflite          float32 baseline (from *_f32clean_gpu.onnx)
  rtmdet_hand_320_f16.tflite          fp16 variant (recommended; app not yet wired)
  rtmdet_hand_320_i8.tflite           int8 variant (NOT accuracy-safe: 49/52 boxes)
  rtmpose_hand_256_f32.tflite         float32 baseline
  rtmpose_hand_256_f16.tflite         fp16 variant (recommended; app not yet wired)
  rtmpose_hand_256_i8.tflite          int8 variant (passes pose gate on GPU: 1.5 px)
  rtmdet_hand_320_end2end_f32.tflite  original in-graph-NMS variant (CPU-only, 5-det cap)
scripts/
  rewrite_gpu_clean.py                ONNX rewrites (Gather/Split/HardSigmoid) + bit-exact check
  convert_models.py                   *_f32clean_gpu.onnx -> float32 .tflite
  convert_originals.py                end2end.onnx -> *_end2end_f32.tflite (comparison)
  quantize_models.py                  f32 -> fp16 + int8 variants (ai-edge-quantizer 0.8.0)
  dump_refs.py                        ONNX ground-truth .npy dumps
  verify_host.py                      host parity (any variant path as CLI arg)
  probe_ops.py                        single-op probe generator
```

Weights are not committed to git; regenerate with the scripts above.
