# MobileCLIP-S1 combined tflite — run, quantize & compare

Source: `ml/original_models/mobileclip2_tflite/mobileclip_s1_datacompdr_last.tflite`
(https://huggingface.co/anton96vice/mobileclip2_tflite, Apache-2.0).

This is a **single-graph MobileCLIP-S1** model: both the vision and text encoders
in one `.tflite` (fp32 weights, 339.9 MB), image tower at **256×256** (vs the
app's current MobileCLIP-B pair at 224×256 input shapes in two ONNX files).

| I/O | name | shape | dtype |
|---|---|---|---|
| in  | `serving_default_args_0:0` | `[1, 3, 256, 256]` | float32 (pixel values) |
| in  | `serving_default_args_1:0` | `[1, 77]` | int64 (token ids) |
| out 0 | `StatefulPartitionedCall:1` | `[1, 512]` | float32 **text** embedding |
| out 1 | `StatefulPartitionedCall:0` | `[1, 512]` | float32 **image** embedding |
| out 2 | `StatefulPartitionedCall:2` | scalar | float32 (aux) |

> Gotcha worth recording: the two 512-d outputs are **swapped relative to their
> order in the graph** — out 0 varies with the token ids (text) and is constant
> under image change; out 1 varies with the image and is constant under text
> change (verified empirically, see `clip_s1_verify.py`). Trust the
> input/output *behavior*, not the tensor names.

## Preprocessing

**/255-only wins decisively**, same as the app's current MobileCLIP-B check
(`onnx/preprocess_normalization_check.py`): resize shortest edge → 256, center
crop 256×256, rescale by 1/255, **no** ImageNet mean/std normalization.

| preprocessing | task (6 samples, top-1) |
|---|---|
| /255 only | **5/6** |
| /255 + CLIP norm (mean/std) | 0/6 |

## Host results (macOS, XNNPACK CPU delegate, this repo's ml/litert venv)

| variant | size | task (top-1) | cos img vs f32 | cos txt vs f32 | lat median (n=15) |
|---|---|---|---|---|---|
| f32 (original) | 339.9 MB | 5/6 | 1.0000 | 1.0000 | 193.0 ms |
| **fp16** (FLOAT_CASTING) | **170.6 MB** (2.0×) | **5/6** | **1.0000** | **1.0000** | 193.0 ms |
| int8 (dynamic wi8c) | **87.9 MB** (3.9×) | 4/6 | 0.8219 | 0.9967 | **69.4 ms** |

- The one f32 miss (`cat_and_lemons.jpg` → "cat lying with lemons") is shared by
  fp16 (cos 1.0 vs f32 — bit-parity) and is a near-tie at the model level, not a
  quantization artifact.
- int8 flips a *second* sample (`a_can_of_tuna.jpg` → "cat and lemons") and the
  image embedding drifts to cos 0.82 vs f32. Per the accuracy-safe-quantization
  skill, host int-kernel numbers are pessimistic — the verdict belongs on the
  device GPU — but the flip pattern says int8 is not the quality row here.

## vs the app's current MobileCLIP-B (ONNX, two sessions, 224px)

| pipeline | size | task (top-1, 4 onnx samples) | combined host lat |
|---|---|---|---|
| current ONNX B pair (vision+text) | 300.1 MB (173.0 + 127.1) | 4/4 | 143.8 ms (101.2 v + 42.6 t) |
| S1 combined fp16 | 170.6 MB | 5/6 (6 samples) | 193.0 ms |
| S1 combined int8 | 87.9 MB | 4/6 (6 samples) | 69.4 ms |

Same 5/6 on the overlapping sample set for fp16; int8 trades one extra miss for
2.8× host speed. The S1 graph is one file/one session instead of two, and int8
gets the whole pipeline under 70 ms on host CPU.

## GPU (on-device) status — strict residency NOT reachable (text path)

Every op in the S1 graph has now been single-op probed on-device
(`ml/litert/scripts/probe_ops.py` + `gpuOpSupportProbe`, Redmi MIUI V12 / Adreno
GPU, strict `Accelerator.GPU` compile under OPENCL / TEXTURE_2D / external-tensor
configs). Results:

| op | count | GPU verdict |
|---|---|---|
| CONV_2D, DEPTHWISE_CONV_2D, FULLY_CONNECTED, BATCH_MATMUL | 91/82/58/32 | SUPPORTED |
| GELU, RSQRT, SQUARED_DIFFERENCE | 62/25/25 | SUPPORTED |
| RESHAPE, TRANSPOSE, ADD, MUL, SUB, DIV, MAXIMUM, MEAN, SLICE, SUM, PAD, LOGISTIC, SQRT, SOFTMAX, ABS | (rest) | SUPPORTED |
| **CAST** (int64→int32 token ids) | 1 | **NOT_SUPPORTED** ("Failed to compile model") |
| **EMBEDDING_LOOKUP** (token embedding table) | 1 | **NOT_SUPPORTED** ("Failed to compile model") |

(The 3 `DELEGATE` ops in the inventory are structural partition markers from
the source conversion, not compute — they are fine.)

**Verdict: the combined S1 graph cannot reach strict GPU residency as-is.** The
vision tower is fully GPU-clean, but the text path starts with `CAST` (int64→
int32) + `EMBEDDING_LOOKUP` (the 49408×512 token table), and the ClGl
accelerator compiles none of those. In a CPU+GPU hybrid config they run on CPU
and the rest on GPU — which is fine for the app's usage (text is encoded once
per query, vision per image) but is not *strict* residency.

Options if strict GPU is required:
1. **Cut the text head** (RTM-style surgery): feed pre-looked-up token embeddings
   `[77, 512]` as a graph input and drop CAST/EMBEDDING_LOOKUP from the tflite.
   The tokenizer + embedding table already live on the CPU side of the app, so
   this matches the existing architecture and makes the whole graph GPU-clean.
2. Accept the hybrid: text path on CPU (~few ms for one query), vision on GPU.

Re-run the probe suite after any model change:

```
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r \
  -e class isao.photorate.android.LiteRtOnDeviceVerificationTest#gpuOpSupportProbe \
  isao.photorate.test/androidx.test.runner.AndroidJUnitRunner
```

## Files

```
ml/litert/scripts/
  clip_s1_verify.py      host task check + latency (any variant as arg)
  clip_s1_parity.py      cross-variant parity table (cosine vs f32)
  quantize_clip_s1.py    fp16 + int8 via ai-edge-quantizer 0.8.0
  probe_ops.py           + GELU / RSQRT / SQUARED_DIFFERENCE / ABS / CAST /
                          EMBEDDING_LOOKUP probes (full S1 op inventory)
ml/litert/converted/
  clip_s1_combined_f16.tflite   170.6 MB (quality row: 2× smaller, bit-parity)
  clip_s1_combined_i8.tflite     87.9 MB (speed row: 3.9× smaller, 2.8× faster)
```
