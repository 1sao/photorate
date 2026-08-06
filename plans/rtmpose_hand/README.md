# RTMPose hand landmark verification (official-style reference pipeline)

Reference implementation of the **official MMPose RTMPose hand 2d (21 keypoints)**
pipeline, run against the exact ONNX models this app ships, so you can compare
"what these models are capable of" against what `AndroidOnnxHandLandmarker`
actually does. Produces overlay images: detection box + detection score, the 21
COCO-WholeBody keypoints colored per finger, and per-keypoint / mean
confidence — up to 2 hands.

- `overlay_hands.py` — the script. Input: one image or a directory. Output:
  overlaid images (+ `manifest.csv` in batch mode).
- `compare_letterbox.py` — A/B of the two RTMDet letterbox conventions
  (see Findings).
- `from_mmpose/` — files pulled verbatim from open-mmlab/mmpose (2026-08-03)
  so the processing can be checked against upstream.
- `output/` — overlays for every `plans/samples` image using the **official**
  letterbox (pad 114, centered).
- `output_app_det/` — same, using the **app's** letterbox (pad 0, top-left).
- `output_rot/` — the **app's rotation search** (`--app-det --rotations`):
  winner rotation + confidence + gesture per hand, `GATED` on near-misses.
- `output_cs/` — **contact sheets** (`--contact-sheet`): official deg-0 vs
  the app's rotation search, side by side per sample, plus a combined grid
  `contact_sheet_all.jpg`.

## Run it

Uses the existing Python venv (onnxruntime + opencv already installed):

```bash
# one image
./onnx/.venv/bin/python plans/rtmpose_hand/overlay_hands.py plans/samples/5/5_vertical.jpg --output /tmp/out.png
# batch over all samples (official letterbox)
./onnx/.venv/bin/python plans/rtmpose_hand/overlay_hands.py --input-dir plans/samples --output-dir plans/rtmpose_hand/output
# batch with the app's letterbox (and any combo via --det-pad / --det-align)
./onnx/.venv/bin/python plans/rtmpose_hand/overlay_hands.py --input-dir plans/samples --output-dir plans/rtmpose_hand/output_app_det --app-det
# the app's rotation search (label per hand: winning rotation + confidence)
./onnx/.venv/bin/python plans/rtmpose_hand/overlay_hands.py <img> --app-det --rotations
./onnx/.venv/bin/python plans/rtmpose_hand/overlay_hands.py --input-dir plans/samples --output-dir plans/rtmpose_hand/output_rot --app-det --rotations
# probe a single crop rotation (the app searches 0/90/180/270; official uses 0)
./onnx/.venv/bin/python plans/rtmpose_hand/overlay_hands.py <img> --rotate-deg 90
# the app's edge thumb-only fallback (partial hands at the image edge: 5_kimbo)
./onnx/.venv/bin/python plans/rtmpose_hand/overlay_hands.py <img> --app-det --rotations --edge-fallback
# letterbox A/B report
./onnx/.venv/bin/python plans/rtmpose_hand/compare_letterbox.py
```

Key flags: `--max-hands` (default 2), `--det-thr` (0.25), `--kpt-thr` (0.3,
keypoints below it are drawn hollow), `--full-res` (skip the app-style min-dim
640 scale-down), `--det-pad {0,114}`, `--det-align {center,top-left}`,
`--rotate-deg {0,90,180,270}`, `--rotations`, `--contact-sheet`,
`--edge-fallback` (when the main pass finds no hands, probe the left/right
edge strips for a partial-hand thumbs-up and label it UNCERTAIN — mirrors
`AndroidOnnxHandLandmarker.edgeThumbFallback`).

## `--contact-sheet`: eyeball official vs app side by side

Renders the same image twice on one canvas — left panel: the **official-style
deg-0** pass (official letterbox, pad 114 centered, raw model output); right
panel: the **app's rotation search** (app letterbox, pad 0 top-left, winner
rotation + gesture labels). Both panels run on the same decoded image, so a
difference in detected boxes between panels is a real letterbox effect.

```bash
# one image
./onnx/.venv/bin/python plans/rtmpose_hand/overlay_hands.py <img> --contact-sheet
# all samples: per-sample pairs into output_cs/ + output_cs/contact_sheet_all.jpg
./onnx/.venv/bin/python plans/rtmpose_hand/overlay_hands.py --input-dir plans/samples --contact-sheet
```

What jumps out immediately:

| sample          | official (deg-0)                   | app (rotations)           |
|-----------------|------------------------------------|---------------------------|
| 1/1_coffee.jpg  | **no hands** (det 0.13 below gate) | 2 hands, THUMBS-1         |
| 2/2_peanuts.jpg | 1 hand, kp 0.22 (hollow)           | 2 hands, 0.85× crops      |
| 4/4.jpg         | 2 hands, no gesture                | 1 hand, rot 0 THUMBS-5    |
| 3/3_ok_sign.jpg | 1 hand, kp 0.70 (no label)         | 1 hand, OK_SIGN-3 kp 0.72 |

## `--rotations`: the app's rotation search, labeled per hand

`--rotations` runs `AndroidOnnxHandLandmarker.rtmposeRating` **as the app does**
(verified against `plans/benchmarks/rtmpose_only_v5.py`, the accepted Python
mirror, plus the Android crop code itself):

- each box is tried at the **1.2× and 0.85×** crop expansions (`FALLBACK_FACTORS`)
  × rotations **0/90/180/270**;
- the crop is made with the app's **exact** chain — pad into a diagonal-sized
  canvas, `boundingBoxFromRotatedRect`, interpolated rotate-about-center,
  center-crop back to `side` (pixel-identical to v5's port of the same chain;
  the side uses the app's corner-rounded `expandSquare`);
- **gesture classification runs in the rotated-crop frame** (the app rotates
  the crop so the hand appears upright — the THUMBS angle is measured against
  that frame's "up", not the image's);
- only reads that form a gesture enter `imageSpace` (deg 0) / `bestRotated`;
  when the 1.2× pass's deg-0 read is confident (kp ≥ 0.3) the search exits
  early — the rotated sweep and the 0.85× pass cannot change the winner
  (image-space preference; verified: identical winners on all samples, 40%
  fewer RTMPose runs — see Findings 2d);
- winner: image-space read when kp ≥ 0.3, else best rotated / highest-kp read
  at or above the app's kp floor (0.3), else the hand is **dropped** — the
  overlay keeps the near-miss but labels it **`GATED`**;
- the palm-rotation model is **not** used (it was removed from the shipped
  pipeline).

Label format per hand: `det 0.76 | rot 0(1.2x) kp 0.52 THUMBS-5 (10/21 ge 0.5)`.
Yellow label = upright winner; orange = rotated winner; `(0.85x)` = the tight
crop won; `no-gesture` = no recognized gesture (the app's rater rejects these);
`GATED` = winner below the app's 0.3 kp floor (the app drops the hand). The
manifest's `rot_kps` column lists every factor×rotation kp mean, e.g.
`1.2x[0:0.56,90:0.51,180:0.49,270:0.52]` (factors only if the pass ran;
early-exited hands list only the rotations that actually ran — missing
entries are the skipped dead work, not a regression).

## How it processes images (following the official examples)

Pipeline mirrors `from_mmpose/topdown_demo_with_mmdet.py` + the hand5
val_pipeline in `from_mmpose/rtmpose-m_8xb256-210e_hand5-256x256.py`:

1. **Detect** — RTMDet-nano hand, 320×320 letterbox, RGB, mean/std
   (123.675, 116.28, 103.53)/(58.395, 57.12, 57.375). NMS is baked into the
   exported ONNX. `from_mmpose/rtmdet_nano_320-8xb32_hand.py` defines the
   official test pipeline (pad **114**, centered); the app pads **0**,
   top-left — both are exposed via flags (the model graph starts directly with
   a Conv, so the letterbox is a caller choice).
2. **Pose** — for each of the top-2 boxes: centered square of side
   max(w, h) (contains the whole box), then the mmpose top-down affine
   (1.25× bbox → 256×256) and BGR mean/std. The ONNX has the SimCC decode
   baked in; it multiplies `bboxes_width_height` by 1.25 internally (constant
   `post_Constant_2 = 1.25`), which is exactly the affine's scale factor, and
   returns [21, 3] = x, y, confidence in the crop's pixel space.
3. **Overlay** — box + det score + skeleton (official per-finger link colors
   from `from_mmpose/coco_wholebody_hand.py`) + mean kp confidence.

## Deliberate differences from the app (AndroidOnnxHandLandmarker)

Default (official-style) mode vs the app; `--rotations --app-det` closes all
of these gaps except the decode (and the palm model, which the app no longer
uses either):

| step               | app                                          | this script (official-style)              | this script (`--rotations`)          |
|--------------------|----------------------------------------------|-------------------------------------------|--------------------------------------|
| detector letterbox | pad 0, top-left                              | pad 114, centered (official) — switchable | pad 0, top-left (`--app-det`)        |
| crop               | 1.2× and 0.85× square                        | 1.0× square (max(w,h))                    | 1.2×/0.85×, exact app chain          |
| rotations          | 0/90/180/270 search                          | 0 only (probe via `--rotate-deg`)         | 0/90/180/270                         |
| rating             | custom gesture classifier + confidence gates | raw model outputs only                    | classifier + winner chain + kp floor |
| decode             | min-dim 640                                  | min-dim 640 (`--full-res` to disable)     | min-dim 640                          |

## Findings

### 1. Letterbox alignment changes detections — the app's choice wins on these samples

`compare_letterbox.py` runs both conventions over the sample set. Top
detection scores (official = pad 114/centered, app = pad 0/top-left):

| sample          | official | app      | note                              |
|-----------------|----------|----------|-----------------------------------|
| 1/1_coffee.jpg  | 0.13     | **0.38** | official misses (below 0.25 gate) |
| 1/1_pills.jpg   | 0.14     | **0.29** | official misses                   |
| 2/2_coffee.jpg  | 0.17     | **0.40** | official misses                   |
| 2/2_peanuts.jpg | 0.26     | **0.45** | both find it                      |
| 4/4.jpg         | 0.62     | **0.76** | both find it                      |

The 2×2 isolation (pad 0/114 × top-left/centered) shows the **alignment** is
the decisive variable; the pad value is nearly irrelevant. With the official
centered letterbox, three scored hands (1_coffee, 1_pills, 2_coffee) score
0.13–0.17 and would be dropped by the app's 0.25 gate; the app's top-left
letterbox scores them 0.29–0.42. **The app's detection preprocessing is not a
regression vs the official one on these samples — it is better.** (Mechanism
untested; likely the exported model behaves best when content sits flush in
the canvas corner. The `output/` vs `output_app_det/` folders let you compare
visually.)

### 2. Deg-0 (official-style) confidence is weak exactly where the app needs its rotation search

The app searches 0/90/180/270 because some samples cannot be rated upright.
The official-style deg-0 pass confirms it: `4/4.jpg` kp-mean 0.44 (5/21 ≥ 0.5),
`4/4_also.jpg` kp-mean 0.30 (2/21), `2/2_peanuts.jpg` kp-mean 0.19 — hollow
keypoints in the overlays. Score-3/5 samples are strong at deg-0
(3_ok_sign kp 0.72, 5_vertical 0.59, 5_rock 0.58).

### 2b. What the app's rotation search actually picks (from `output_rot/`)

Run with `--app-det --rotations`, the app-faithful search rates most samples
upright at **rot 0** (THUMBS-1 1_coffee/1_pills, THUMBS-5 5_vertical/5_kenya/
5_trope/5_also/5_horizontal, OK_SIGN-3 3_ok_sign*/3, ROCK-5 5_rock*).
Notable rotated / fallback reads (all visible in `output_rot/`):

- `2/2_peanuts.jpg` — both hands fall back to the **0.85×** crop (the app's
  tight-crop path for tall/thin boxes); no gesture at the winner, so the app
  rates nothing.
- `2/2_coffee.jpg` — hand 2 wins at **rot 180** (THUMBS-4, kp 0.32): the
  deg-0 read (kp 0.32) forms no gesture, so the rotated read wins. A false
  read vs ground truth (2) — exactly the kind of behavior this mode exists to
  expose.
- `4/4.jpg` — **rot 0 THUMBS-5** (kp 0.52). The app's code comment claiming
  "reads THUMBS-4 at 90°" is **stale** (it predates the sparse-model removal
  and the image-space-preference rule): the current committed pipeline rates
  4/4 upright. `rtmpose_only_v5.py` agrees (`0.52@0/THUMBS-5`).
- `4/4_also.jpg` — rot 0 THUMBS-5 (kp 0.32) with the app's exact crop; the
  v5 mirror reads ROCK-5@180 with its 1-px-larger crop (the app code and v5
  disagree on the crop side by one pixel — borderline case; this overlay
  follows the app code).
- `3/3_open_hand_palm_down.jpg` — no-gesture winner at rot 90 (kp 0.79):
  OPEN_PALM was dropped from the gesture set, so the app rates nothing.
- `1/1_pills.jpg` hand 2 — `GATED` (THUMBS-3, kp 0.27): below the app's
  0.3 kp floor, the app drops it.

`no_score/*` samples show no gestures (holding/folded hands), as intended.

### 2c. Edge thumb-only fallback + the score-direction fix

Two changes landed in the app pipeline and are mirrored here:

- **`--edge-fallback`** — a hand mostly out of frame at the left/right image
  edge is invisible to full-frame RTMDet (`5/5_kimbo.jpg`, best full-frame det
  0.17). The fallback probes a single half-width strip per edge over the
  lower 60% of the image (padded to square with 114 gray), re-detects, and
  keeps a read whose thumb chain is confident (mean kp1..4 ≥ 0.45) with
  unreliable fingers (mean kp5..20 < 0.40) pointing up-ish (≤ 45° from up).
  It rates a **low-certainty** THUMBS guess (`5_kimbo`: THUMBS-5, kp 0.39,
  UNCERTAIN). The confidence gates are what keep `no_score_hands.jpg`'s
  right-edge read (det 0.30, thumbC 0.30) from firing. **Deg-0-first
  acceptance**: the upright read is the only gate-qualifying rotation on
  5_kimbo (deg 0 passes at angle 22.4°; 90° fails on angle, 180° on kp,
  270° on angle), so a qualifying deg-0 read is accepted immediately and the
  rotated sweep is skipped (the sweep still runs when deg 0 fails — a
  sideways thumb can still qualify rotated). Prunes the strip scan from 4
  RTMPose runs to 1 (5_kimbo on-device: 1230ms -> 497ms mean). NOTE: this
  changes the selection from "best of all qualifying rotations" to "first
  qualifying (deg-0)" — behavior-identical on the current dataset (deg-0 is
  the only qualifier on 5_kimbo), but a future sample with a qualifying
  deg-0 AND a higher-thumbConf rotated read would now pick deg-0. Cost: 2 strip
  detections per hand-less image (was 6 with three y-bands), and 0 when the
  full-frame detector's best box is below 0.10 (`EDGE_HINT_DET` — an edge
  hand still leaves a weak full-frame response, kimbo 0.167; kept at 0.10
  because the zoomed strip detection is largely independent of the full-frame
  response, so a more-out-of-frame hand could still read a confident thumb
  even below 0.12). Tradeoff: an edge hand in the top 40% of the image would
  be missed.
- **THUMBS score direction** — the score now uses the thumb's **IP→TIP
  segment (kp3→kp4)** instead of MCP→TIP (kp2→kp4); the tf_angle gate keeps
  kp2→kp4 (switching the gate flipped 1_coffee to THUMBS-5). The CMC/MCP
  joints are noisy (low kp confidence) and the thumb is often curved, so the
  tip segment is where the pointing lives. Result: `1/1_tuna.jpg`'s borderline
  147.7°/153° angle now reads its label's **ONE** (was TWO); every other
  sample is unchanged (LSQ fits through kp1..4 were tried and rejected — they
  flip 5_buco/5_kenya/5_vertical to THUMBS-1).

### 2d. Rotation search early exit (−40% RTMPose runs, identical results)

Measured with `plans/benchmarks/rot_analysis.py`: the app's full search
costs up to 8 RTMPose runs per box (1.2× and 0.85× crop factors ×
0/90/180/270). When the deg-0 read on the 1.2× crop is confident
(kp ≥ 0.3), the winner rule already prefers it, so every other rotation is
provably dead work. Skipping it: **identical winners on all 33 samples /
39 boxes, runs 204 → 123 (−40%)**. On-device (2026-08-04): emulator
9.1→6.0s, Pixel 9a 43.6→30.4s (−35%), all ratings unchanged (`OK (1 test)`
both devices). The rotation search itself is still necessary — 6 boxes
(2_coffee, 2_peanuts, 1_pills, 3_open_hand_palm_down,
no_score_holding_spray_bottle) only get their best read at 90°/180° — the
edge fallback only covers the no-hands-found case and cannot replace it.

### 2e. Per-image latency: the slow images are the full-search ones (stable)

Per-image `detectedMs` over repeated runs on the Pixel 9a: the same set of
images is always the slowest — those whose hands fail a confident deg-0 read
and pay the full 8-run search (`no_score_holding_in_hand` 3.6–5.1s,
`2_peanuts`, `2_coffee`, `no_score_holding_spray_bottle`, `1_pills`).
No-hand images cost ~0.25s (one detector call + hint-gated edge fallback).
After the early exit, confident images dropped from ~1–2.5s to ~0.3–0.6s;
the full-search set still dominates worst-case gallery-scan latency.

### 2f. NNAPI: dead end for these exports; fp32 re-export charted

Full NNAPI A/B on a Pixel 9a (Tensor G4) + emulator, 2026-08-04: every
configuration is slower than plain CPU or breaks correctness. The darwinn
driver rejects any non-float32 operand, and both models bake int64-heavy
post-processing into the graph (RTMPose: int64 bbox input + int64 simcc-
decode internals; RTMDet: baked NonMaxSuppression). Plain NNAPI / detector-
only fall back to the CPU-based nnapi-reference backend (65.6s / 55.0s vs
43.6s CPU). The NNAPI flags don't help: `CPU_DISABLED` hard-fails session
creation (0/38 ops supported), `USE_FP16` engages 2 TPU subgraphs but shifts
outputs (a no_score hand starts rating) with no speedup.

`plans/benchmarks/export_fp32_models.py` re-exports both models to
`onnx/models_fp32/` (originals untouched) and proves the surgery:
`rtmpose_hand_fp32.onnx` (float32 bbox input, redundant Cast removed) is
BIT-IDENTICAL in Python (156 crops, max diff 0.0), and the NMS-stripped
`rtmdet_n_hand_fp32.onnx` + caller-side NMS reproduces every real detection
exactly. But even the fp32 pose still gets 0 TPU subgraphs on-device (the
int64 decode internals poison the graph), so NNAPI remains slower. The real
fix — re-exporting the models without baked decode/NMS and doing the
post-processing caller-side — is charted in that script, but its payoff is
unproven (partial TPU engagement showed no speedup), so production stays on
plain CPU.

### 3. Models

The shipped `onnx/models/rtmdet_n_hand.onnx` + `rtmpose_hand.onnx` are
official mmpose exports (opset 11, producer pytorch 1.9; the pose model has
the SimCC decode + 1.25× bbox de-normalization baked in) — same family as the
official hand5 models below. Official checkpoints for reference:

- RTMDet-nano hand:
  `https://download.openmmlab.com/mmpose/v1/projects/rtmposev1/onnx_sdk/rtmdet_nano_8xb32-300e_hand-267f9c8f.zip`
- RTMPose-m hand5 (onnx):
  `https://download.openmmlab.com/mmpose/v1/projects/rtmposev1/onnx_sdk/rtmpose-m_simcc-hand5_pt-aic-coco_210e-256x256-74fb594_20230320.zip`
- RTMPose-m hand5 (pth):
  `https://download.openmmlab.com/mmpose/v1/projects/rtmposev1/rtmpose-m_simcc-hand5_pt-aic-coco_210e-256x256-74fb594_20230320.pth`
- Model card: `from_mmpose/rtmpose_hand5.md` (Hand5: PCK@0.2 96.4, AUC 83.9).

## from_mmpose provenance

All files are unmodified copies from `open-mmlab/mmpose` (branch `main`,
pulled 2026-08-03):

| file                                     | upstream path                                                                                                                        |
|------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| `topdown_demo_with_mmdet.py`             | `demo/topdown_demo_with_mmdet.py` (needs torch + mmcv + mmdet + mmpose; pulled for reference — this repo's venv is onnxruntime-only) |
| `rtmpose-m_8xb256-210e_hand5-256x256.py` | `configs/hand_2d_keypoint/rtmpose/hand5/rtmpose-m_8xb256-210e_hand5-256x256.py`                                                      |
| `rtmdet_nano_320-8xb32_hand.py`          | `projects/rtmpose/rtmdet/hand/rtmdet_nano_320-8xb32_hand.py`                                                                         |
| `coco_wholebody_hand.py`                 | `configs/_base_/datasets/coco_wholebody_hand.py` (keypoint/skeleton meta info)                                                       |
| `rtmpose_hand5.md`                       | `configs/hand_2d_keypoint/rtmpose/hand5/rtmpose_hand5.md`                                                                            |
| `examples_README.md`                     | `projects/rtmpose/examples/README.md`                                                                                                |
| `rtmpose_project_README.md`              | `projects/rtmpose/README.md`                                                                                                         |

Note: the rtmpose project's old `2_RTMPose_inference_with_onnxruntime_in_python`
Python example no longer exists upstream (it 404s on `main` and every tag; the
examples README now lists it without a link). The current upstream recommendation
for dependency-free inference is
[rtmlib](https://github.com/Tau-J/rtmlib). That example is why this script was
created instead of being pulled; its processing is replicated from the demo +
configs above.
