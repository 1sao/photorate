# How to obtain models used in the app

See `SOURCE.md` files for original model sources. Download manually.

Run the following scripts from the repo root. All scripts take path flags (`--orig-dir`,
`--out-dir`, `--sample`, …) with the repo-standard locations as defaults.

Two Python envs, pinned to the verified versions:

```bash
# litert env — everything except make_f32clean
python3.11 -m venv ~/.venvs/photorate-litert
~/.venvs/photorate-litert/bin/pip install \
  onnx==1.17.0 onnxruntime onnx_graphsurgeon sng4onnx \
  onnx2tf==1.28.8 tensorflow==2.18.0 ai-edge-litert==2.1.6 ai-edge-quantizer==0.8.0 \
  numpy tokenizers pillow opencv-python

# onnx env — make_f32clean only
python3 -m venv ~/.venvs/photorate-onnx
~/.venvs/photorate-onnx/bin/pip install "onnx>=1.22" "onnxruntime>=1.29" onnxsim==0.7.3 numpy
```

**RTM hand models (LiteRT):**

```bash
L=~/.venvs/photorate-litert/bin/python
O=~/.venvs/photorate-onnx/bin/python

$O ml/litert/scripts/make_f32clean.py --write
# mmdeploy end2end.onnx -> end2end_f32clean.onnx in ml/original_models/.
# Strip things we can't make GPU-compatible

$L ml/litert/scripts/rewrite_gpu_clean.py
# -> end2end_f32clean_gpu.onnx (GPU-compatible op rewrites)

$L ml/litert/scripts/convert_models.py
# -> ml/litert/converted/rtmdet_hand_320_f32.tflite, rtmpose_hand_256_f32.tflite (used in the app)

$L ml/litert/scripts/quantize_models.py
# -> converted/rtm*_{f16,i8}.tflite (not used in the app due to minor regressions introduced by quantization)

$L ml/litert/scripts/dump_refs.py
# -> ml/litert/refs/*.npy (provide your own sample images)
# Used by verify_host.py

$L ml/litert/scripts/verify_host.py
# Check for regressions
```

**MobileCLIP-S1 (LiteRT search):**

```bash
L=~/.venvs/photorate-litert/bin/python

$L ml/litert/scripts/quantize_clip_s1.py
# -> ml/litert/converted/clip_s1_combined_f16.tflite (used in the app) + _i8.tflite

cd ml/litert/scripts && $L clip_s1_parity.py
# embedding-cosine + task-ranking check vs the fp32 source (provide your own sample images)
```

**ONNX backend (Android):**

`text/vision_model_fp16.onnx` and `tokenizer.json` should be downloaded and used as is. For the
rest, the `end2end_f32clean.onnx` files come from the `make_f32clean.py --write` step; copy them to
`ml/onnx_hand/<same-rel-path>/`.
