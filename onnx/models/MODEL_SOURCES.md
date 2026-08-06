# ONNX Model Sources

Provenance record for the ONNX models shipped in `onnx/models/`. Compiled 2026-08-02 by
auditing the repo notes, inspecting the ONNX graphs (see `view_model_metadata.py`), and web
research.

Confidence legend:

- ✅ — source explicitly documented in the repo (`plans/benchmarks/*.md`).
- 🟡 — model family/architecture identified by graph forensics; exact download unverified.
- ❓ — source family known, exact origin (which conversion/download) unrecorded.

> Note: `onnx/` is gitignored (`/.gitignore: /onnx/`), so this file and the models are not
> under version control. If provenance must be tracked in git, add `onnx/models/MODEL_SOURCES.md`
> to an exception or move it into `plans/`.

## Quick reference

| File | Size | What it is | Source | Conf. |
|---|---|---|---|---|
| `rtmdet_n_hand.onnx` | 4.0 MB | RTMDet-nano hand detector (320×320) | [bukuroo/RTMDet-ONNX](https://huggingface.co/bukuroo/RTMDet-ONNX) (Apache-2.0) | ✅ |
| `rtmpose_hand.onnx` | 55 MB | RTMPose-m hand keypoints (256×256) | [PINTO_model_zoo/427_RTMPose_Hand](https://github.com/PINTO0309/PINTO_model_zoo/tree/main/427_RTMPose_Hand) (Apache-2.0) | ✅ |
| `gold_yolo_hand.onnx` | 22.6 MB | Gold-YOLO hand detector (640×480) | Gold-YOLO (Huawei Noah's Ark); exact download unrecorded | 🟡 |
| `palm_detection_full.onnx` | 4.8 MB | MediaPipe palm detector (192×192) | Google MediaPipe → tf2onnx; conversion source unrecorded | ❓ |
| `hand_landmark_sparse.onnx` | 10.6 MB | MediaPipe sparse hand landmarker (224×224) | Google MediaPipe → tf2onnx; conversion source unrecorded | ❓ |
| `text_model_fp16.onnx` | 127 MB | CLIP text encoder (512-d) | **EVA-02-CLIP-B/16** (BAAI) fp16 export — NOT MobileCLIP | 🟡 |
| `vision_model_fp16.onnx` | 173 MB | CLIP vision encoder (224×224, 512-d) | **EVA-02-CLIP-B/16** (BAAI) fp16 export — NOT MobileCLIP | 🟡 |

## Per-model details

### `rtmdet_n_hand.onnx` — 4.0 MB ✅

- **Source:** https://huggingface.co/bukuroo/RTMDet-ONNX → `rtmdet-n-hand.onnx` (Apache-2.0).
- **What:** MMDetection **RTMDet-nano** hand detector, trained on OneHand10K; ezonnx export with
  NMS baked in. 990K params.
- **Graph:** producer pytorch 1.9, IR 6, opset 11; input `[1, 3, 320, 320]` FLOAT; outputs
  `dets [1, ·, 5]`, `labels [1, ·]` INT64.
- **Preprocessing** (from `plans/benchmarks/detector_benchmark.md`): BGR→RGB, longest side
  scaled to 320 with top-left gray(114) pad, normalize RGB-mean
  [123.675, 116.28, 103.53] / std [58.395, 57.12, 57.375]; score ≥ 0.3 then NMS iou 0.45.
- **Used as:** the app's hand detector (`DETECTOR_ASSET` in `photosOnnx`); replaced Gold-YOLO.

### `rtmpose_hand.onnx` — 55 MB ✅

- **Source:** PINTO_model_zoo entry `427_RTMPose_Hand`, file
  `rtmpose_hand_Nx3x256x256_with_post.onnx` (Apache-2.0, fp32), renamed locally. Verified by
  `plans/benchmarks/landmark_stage.md`.
- **What:** MMPose **RTMPose-m** hand keypoint model with baked simcc decode (no manual
  soft-argmax needed). 13.76M params. The `-m` is the only official hand5 config in mmpose.
- **Graph:** producer pytorch 1.9, IR 9, opset 11; inputs `[N, 3, 256, 256]` FLOAT +
  `bboxes_width_height [N, 2]` INT64; output `batch_keypoints_xyscore [N, 21, 3]`.
- **Note:** fp16/int8 variants of this model were evaluated and rejected (simcc rounding flips
  borderline thumb ratings) — keep fp32. See `landmark_stage.md`.
- **Used as:** thumb-accurate fallback for hands the sparse landmarker can't rate.

### `gold_yolo_hand.onnx` — 22.6 MB 🟡

- **What:** **Gold-YOLO** (Huawei Noah's Ark Lab) hand detector. 5.63M params, RepVGG-style
  reparametrized backbone (`backbone.ERBlock_*.rbr_reparam`), `LAF_p3` neck — the official
  Gold-YOLO code naming (repo is YOLOv6-based).
- **Graph:** producer pytorch 2.1.0, IR 9, opset 11 (+ai.onnx.ml 2); input `[1, 3, 480, 640]`
  FLOAT (landscape); output `batchno_classid_x1y1x2y2_score [N, 7]` (YOLO-style, NMS baked).
- **Official code:** https://github.com/huawei-noah/Efficient-Computing/tree/master/Detection/Gold-YOLO
  (Apache-2.0).
- **Likely download venues (unverified):**
  - PINTO model zoo `420_Gold-YOLO-Hand` (trained on COCO-Hand; per-size tarballs on Wasabi
    S3 via `download_{n,s,m,l}.sh`): https://github.com/PINTO0309/PINTO_model_zoo/tree/main/420_Gold-YOLO-Hand
    - ⚠️ `detector_benchmark.md` claims PINTO only ships via "a 32 GB tarball"; the actual entry
      ships per-size tarballs, so that note looks wrong.
    - Our file's landscape `[1,3,480,640]` input does NOT match PINTO's portrait "640×480"
      orientation, so the exact match is unconfirmed.
- **Used as:** benchmark baseline only — the app now uses RTMDet as detector
  (`plans/benchmarks/full_pipeline.py` still references it).

### `palm_detection_full.onnx` — 4.8 MB ❓

- **What:** Google **MediaPipe palm detector** — the same model inside the official
  Hand Landmarker `.task` pipeline. 1.17M params.
- **Graph:** producer blank, IR 8, opset 11, graph name `tf2onnx`, doc string
  "converted from saved_model"; input `[batch, 3, 192, 192]` FLOAT; outputs
  `batch_nums [N, 1]` INT64, `score_cx_cy_w_wristcenterxy_middlefingerxy [N, 8]`.
- **Origin:** tf2onnx conversion of Google's `palm_detection_full` model. Google's official
  model files live at https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker#models
  (`storage.googleapis.com/mediapipe-models/...`; referenced from `AndroidGalleryDataSource.kt`).
  The specific ONNX conversion script/repo used is not recorded anywhere in this project.
- **Used as:** palm rotation stage in the ONNX hybrid pipeline (`PALM_ASSET` in `photosOnnx`).

### `hand_landmark_sparse.onnx` — 10.6 MB ❓

- **What:** Google **MediaPipe *sparse* hand landmarker** (the "sparse" variant MediaPipe uses;
  not the full one). 2.64M params.
- **Graph:** producer blank, IR 8, opset 11, graph name `tf2onnx`; input `[N, 3, 224, 224]`
  FLOAT; outputs `xyz_x21 [N, 63]`, `hand_score [N, 1]`, `lefthand_0_or_righthand_1 [N, 1]`.
- **Origin:** same tf2onnx conversion family as `palm_detection_full.onnx` (see above); exact
  source unrecorded.
- **Used as:** presence gate + OK/OPEN_PALM/ROCK rater in the ONNX hybrid pipeline
  (`LANDMARK_ASSET` in `photosOnnx`).

### `text_model_fp16.onnx` + `vision_model_fp16.onnx` — 127 MB + 173 MB 🟡 ⚠️

- **⚠️ These are NOT Apple MobileCLIP**, despite the app's search pipeline being called
  "MobileCLIP" (`AppClipSearch`/`AppClipSearchFactory`) and the neighboring
  `config.json`/`preprocessor_config.json` being MobileCLIP-flavored.
- **What they actually are:** a **ViT-B/16-scale CLIP** pair, structurally identical to
  **EVA-02-CLIP-B/16** (BAAI):
  - vision: 86,348,160 params, 12 transformer blocks, width 768, patch-16 (196 tokens =
    14×14 @ 224), 3-conv patch-embed stem, output 512-d `image_embeds`.
  - text: 63,428,096 params, 12 blocks, width 512, vocab 49408, context 77, output 512-d
    `text_embeds`.
  - combined ≈ 149.8M — matches EVA-02-CLIP-B/16's "149M total" and its **fp16** training
    precision (hence the `_fp16` suffix).
  - layer names (`model.patch_emb`, `model.transformer.N.pre_norm_mha.j.qkv_proj`,
    `embedding_layer`, `projection_layer`, `pos_embed.pos_embed`) are open_clip/timm
    EVA-style.
- **Checkpoint source (most likely):** open_clip `EVA02-CLIP-B-16` (LAION-2B), weights on HF
  `QuanSun/EVA-CLIP` (`EVA02_CLIP_B_psz16_s8B.pt`); repo MIT-licensed. Exported with
  `torch.onnx.export` (producer pytorch 1.13.1) to fp16. **Not byte-verified** — an
  embedding comparison against open_clip is the remaining confirmation step.
- **Graphs:** text — IR 7, opset 14, `input_ids [batch, seq]` INT64 → `text_embeds [batch, 512]`;
  vision — IR 7, opset 12, `pixel_values [batch, 3, 224, 224]` FLOAT → `image_embeds [batch, 512]`.
- **✅ Preprocessing VERIFIED (2026-08-02):** the model expects `/255`-only input. Adding CLIP
  ImageNet mean/std normalization was tested on the same models + sample images
  (`onnx/preprocess_normalization_check.py`) and it BREAKS search: `/255`-only scores 5/6
  top-1 matches, `/255` + normalization scores 2/6 (images start matching wrong descriptions,
  e.g. "Cat"). Normalized image embeddings are nearly orthogonal to the text embeddings
  (similarity to the `/255`-only embedding ≈ 0.18). So the current `/255`-only decode in
  `AndroidAppClipSearch.preprocess()` is CORRECT — MobileCLIP-style preprocessing, matching
  `preprocessor_config.json` (`do_normalize: false`). **Do not add normalization unless the
  model files are replaced.** Note: this /255-only behavior is unlike a vanilla EVA-02-CLIP-B/16
  checkpoint (which expects normalization), so the checkpoint may have been adapted — see
  unresolved item 3.
- **Related models seen elsewhere (not these):** PicQuery's `script/model-CLIP` exports
  OpenAI CLIP ViT-B/32 (`clip.load("ViT-B/32")`, width-768 patch-32, `vit_to_onnx.py`); its
  `script/model-MobileCLIP2` exports MobileCLIP2-S0 TFLite (256×256 input). Both are different
  from the ONNX files here.

## Unresolved items / next steps

1. **Gold-YOLO exact download** — identify which repo/mirror produced our
   `gold_yolo_hand.onnx` (compare `download_{n,s,m,l}.sh` tarballs or check the landscape
   input shape against the official Gold-YOLO hand export).
2. **MediaPipe ONNX conversion source** — `palm_detection_full.onnx`/`hand_landmark_sparse.onnx`
   are tf2onnx outputs; the conversion script/repo isn't recorded. If re-downloading, prefer
   the official `.task` bundles + document the conversion.
3. **CLIP checkpoint byte-verification** — install torch + open_clip in `onnx/.venv`, load
   `EVA02-CLIP-B/16` (and OpenAI `ViT-B/32` as a control), compare text-embedding cosine
   similarity with our ONNX models for a few prompts. Note: the /255-only behavior (see the
   preprocessing note) already argues against a vanilla EVA-02-CLIP-B/16 checkpoint, so the
   structural match may be coincidental or the checkpoint was adapted.
4. ~~**Preprocessing check**~~ — **RESOLVED (2026-08-02):** keep the `/255`-only decode in
   `AndroidAppClipSearch.preprocess()`; verified empirically that ImageNet normalization
   breaks top-1 (`onnx/preprocess_normalization_check.py`).

## How the models are used in the app

| File | Kotlin constant | Pipeline role |
|---|---|---|
| `rtmdet_n_hand.onnx` | `AndroidOnnxHandLandmarkerFactory.DETECTOR_ASSET` | hand detection |
| `palm_detection_full.onnx` | `PALM_ASSET` | palm rotation for landmark crops |
| `hand_landmark_sparse.onnx` | `LANDMARK_ASSET` | presence gate + gesture rating |
| `rtmpose_hand.onnx` | `RTMPOSE_ASSET` | thumb-accurate fallback rating |
| `gold_yolo_hand.onnx` | — (benchmarks only) | former detector, now baseline |
| `text_model_fp16.onnx` / `vision_model_fp16.onnx` | `AppClipSearch.TEXT_MODEL_ASSET` / `VISION_MODEL_ASSET` | image search embeddings |

## Inspecting / regenerating this table

```bash
onnx/.venv/bin/python onnx/models/view_model_metadata.py onnx/models/*.onnx
```
