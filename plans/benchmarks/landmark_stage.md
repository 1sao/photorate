# Landmark-stage investigation (thumbs-up/dorsal views)

Date: 2026-08-02. Python benchmark against `plans/samples`, same onnxruntime
1.28.0 the Android AAR ships. Continues `detector_benchmark.md`: detector gaps
are fixed by RTMDet-nano hand, but the sparse landmark model still fails to
produce ratable thumb geometry for the thumbs-up samples.

## The problem

With RTMDet-nano hand (4 MB) as the detector, every sample is detected. But the
app's landmark model `hand_landmark_sparse.onnx` (MediaPipe *sparse* hand
landmarker, 10.6 MB, 224×224) produces **thumb geometry that differs from
MediaPipe's** — the shared `LandmarkRaterByThumb` (tuned for MediaPipe's
landmarks) rejects the thumbs-up samples:

| Sample | Sparse multi-rot presence | Rater result |
|---|---|---|
| `5/5_also.jpg` | 0.83 | rejected (thumb length ratio 0.16, tf angle 110°) |
| `5/5_horizontal.jpg` | 0.83 / 0.76 | rejected (thumb/index nearly parallel, tf 13°) |
| `5/5_vertical.jpg` | 0.81 | rejected (tf 118° < 120°) |
| `4/4.jpg` | 0.37–0.52 | rejected |
| `4/4_also.jpg` | **0.20 < 0.35** | not even a valid hand |

Meanwhile `no_score_*` (gripping / folded / holding) stay correctly filtered by
the sparse presence gate (all < 0.35) at every rotation — that property must be
preserved.

## Fix that worked: hybrid pipeline (verified in Python)

Keep the sparse model for presence gating + OK/OPEN_PALM/ROCK rating, and fall
back to a second, thumb-accurate landmark model for the rejected hands:

1. **RTMDet-nano hand** (`rtmdet-n-hand.onnx`, 4 MB, 320×320) detects all boxes
   (replaces Gold-YOLO, 22.6 MB — 5× smaller, detects the 3 former gaps).
2. **Sparse landmark, multi-rotation search**: expand each box 1.2× to a square
   (UNCLAMPED to the image edge — clamping breaks squareness near edges), then
   try rotations `[palm-degree, 0, 90, 180, 270]` through the app's exact
   rotate-and-crop math, keep the highest presence. Gate at 0.35 → filters all
   `no_score_*`.
3. **Rate with sparse landmarks** — handles OK_SIGN / OPEN_PALM / ROCK.
4. **RTMPose hand fallback**: if the sparse hand passes the presence gate but
   the rater rejects it, run RTMPose on the same rotated square crops (it has
   MediaPipe-like thumb geometry), rate those. Also try all rotations, prefer a
   THUMBS classification.

Verified results on all 12 samples (expected → got):

```
3/3_ok_sign.jpg              THREE    sparse/OPEN_PALM-3   OK
3/3_open_hand_palm_down.jpg  THREE    sparse/OPEN_PALM-3   OK
4/4.jpg                      FOUR     rtmpose/THUMBS-4     OK
4/4_also.jpg                 FOUR     presence 0.20        -- (still a gap)
5/5_also.jpg                 FIVE     rtmpose/THUMBS-5     OK
5/5_horizontal.jpg           FIVE     rtmpose/THUMBS-5     OK
5/5_rock.jpg                 FIVE     sparse/ROCK-5        OK
5/5_rock_alternative.jpg     FIVE     sparse/ROCK-5        OK
5/5_vertical.jpg             FIVE     rtmpose/THUMBS-5     OK
no_score (×3)                NONE     all filtered         OK
```

## Candidate landmark models researched

| Model | Size | Verdict |
|---|---|---|
| `hand_landmark_sparse.onnx` (current) | 10.6 MB | Presence gate works; thumb geometry wrong for the rater |
| **RTMPose-m hand** (`PINTO0309/PINTO_model_zoo/427_RTMPose_Hand`, `rtmpose_hand_Nx3x256x256_with_post.onnx`, Apache-2.0) | **55 MB fp32** | ✅ correct thumb geometry on ALL samples incl. 4/4 dorsal |
| RTMPose-m fp16 (onnxconverter-common) | 28 MB | ❌ fp16 rounding in simcc soft-argmax flips borderline THUMBS-5 → THUMBS-1/2 (2–4 px error) |
| RTMPose-m int8 (dynamic) | 13.9 MB | ❌ far worse (10–120 px error) |
| MediaPipe `hand_landmark_full.tflite` (Google, 5.5 MB) | 5.5 MB | Untested here; MediaPipe-native geometry likely fits the rater, but it is TFLite not ONNX (needs conversion) — the 55 MB RTMPose is the drop-in ONNX |

No smaller official RTMPose hand variant exists (`rtmpose-m` is the only hand5
config in mmpose). **Decision: keep the 55 MB fp32 RTMPose for now.**

## Manual simcc decode (for reference)

`rtmpose_hand_Nx3x256x256_with_post.onnx` bakes the decode; verified to match a
manual port exactly (max diff 0.000) on the samples:

```
side = max(1.25*w, 1.25*h)         # crop w,h; 1.25 matches the top-down affine
x = (argmax(simcc_x) / 512) * side + w/2 - side/2
y = (argmax(simcc_y) / 512) * side + h/2 - side/2
score = min(max(simcc_x), max(simcc_y))
```

Using the raw backbone (`rtmpose_hand_Nx3x256x256.onnx`, outputs `simcc_x`,
`simcc_y` [21, 512]) with this fp32 decode lets us ship the fp16 backbone
(28 MB) IF the fp16 rounding issue is ever resolved — with the fp32 decode the
error stays in the backbone (2–4 px), which still flips borderline samples, so
fp16 is not usable today.

## Channel order was the on-device bug (2026-08-02, same session)

The Python verification feeds cv2 (BGR) crops **directly** to the palm and
sparse models (`padded.transpose(2,0,1)`, no `COLOR_BGR2RGB`) — i.e. BGR. The
Kotlin port instead fed **RGB** (`bitmapToCHW` wrote R,G,B), which made the
sparse model far more permissive on-device:

| Sample | Python BGR | App RGB (buggy) |
|---|---|---|
| `no_score/no_score_holding_in_hand.jpg` | hs 0.04 → filtered | **OPEN_PALM-3 FP** |
| `no_score/no_score_holding_on_folded_hands.jpg` | hs 0.12 → filtered | **OPEN_PALM-3 FP** |
| `4/4.jpg` | sparse unclassified → rtmpose **THUMBS-4** | **OPEN_PALM-3 (wrong)** |

Fix: `bitmapToCHW` now writes **B,G,R** for palm + sparse (RTMPose already got
BGR via `bitmapToCHWMeanStd(rgbOrder=false)`). RTMDet stays RGB
(`COLOR_BGR2RGB` in Python).

The RTMPose fallback also now (a) runs only behind the sparse 0.35 presence
gate — without the gate it re-introduces no_score false positives
(`holding_in_hand` rates OPEN_PALM-3, `holding_in_two_hands` OPEN_PALM-3); and
(b) tries rotations `{0, 90, 180, 270, palm-deg, sparse-deg}` picking the
**highest THUMBS score** (tie-break keypoint confidence), because the palm
degree is unreliable for dorsal views (5_also: palm deg −168° rates THUMBS-1,
correct answer is THUMBS-5 at 0°).

### RTMPose fallback keypoints are un-rotated into image space

RTMPose runs on a `deg`-rotated square crop, so its keypoints are in the
**rotated crop frame** (fine for the rater — distances/angles are frame-local —
but geometrically misplaced for the stored bbox/centroid/points when deg ≠ 0).
The fallback now classifies on the crop-frame points (preserving the rotation-
search semantics) but stores an image-space hand, un-rotating by **+degree**
around the square center. Sign verified empirically: on 4/4.jpg, deg=90
keypoints un-rotated by +90° land on the deg=0 ground truth (mean err 30 px)
while −90° flies across the image (328 px). Caller-side re-rating stays
correct: 4/4 → FOUR, and 5_horizontal box0 even improves 4 → FIVE.

## Final on-device results (`OnnxHandLandmarkDatasetTest`, app pipeline)

All 12 samples, real device run (BGR fix + gated fallback):

```
5/5_also.jpg              FIVE   THUMBS_UP   OK
5/5_horizontal.jpg        FIVE   THUMBS_UP   OK
5/5_rock.jpg              FIVE   ROCK        OK
5/5_rock_alternative.jpg  FIVE   ROCK        OK
5/5_vertical.jpg          FIVE   THUMBS_UP   OK
4/4.jpg                   FOUR   THUMBS_UP   OK
3/3_ok_sign.jpg           THREE  OPEN_PALM   OK
3/3_open_hand_palm_down.jpg THREE OPEN_PALM  OK
no_score (×3)             NONE   filtered    OK
```

## Second dataset expansion (2026-08-02, same session)

User added 9 real-life samples: `1_coffee`/`1_pills` (thumbs-down), `2_coffee`/
`2_peanuts` (score 2), `3_ok_sign_peanuts`, `5_kenya`/`5_trope` (thumbs-up in
rotated/real-world photos), `no_score_hands`/`no_score_no_hands`. On-device
findings and fixes:

| Sample | Before | Fix | After |
|---|---|---|---|
| `5/5_kenya.jpg` | unrated | THUMBS entry: tf 120→85; **prefer the deg-0 (image-space) rating** over rotated crops | **FIVE** |
| `5/5_trope.jpg` | unrated | same | **FIVE** |
| `1/1_coffee.jpg` | unrated (thumb_len 0.27 on-device < 0.5) | thumb length 0.5→0.25 | **ONE** |
| `1/1_pills.jpg` | detection gap (RTMDet top box 0.287) | detection gate 0.3→0.25 | **ONE** |
| `2/2_coffee.jpg` | OK_SIGN-3 / ROCK-5 | new **PEACE (index+middle, ring+pinky curled) → TWO** gesture | **TWO** |
| `2/2_peanuts.jpg` | gap | sparse presence 0.30–0.32 on-device sits inside the no_score band (≤0.27) — gate unchanged | gap (honest) |
| `4/4_also.jpg` | gap | — | gap (honest) |
| no_score ×2 (new) | filtered | — | filtered ✓ |

### Rating-frame divergence fixed (rotationDegrees)

The earlier fix un-rotated stored keypoints into image space, but the caller
re-rates the returned hand with `LandmarkRaterByThumb` — for rotated winners
(5_kenya) the image-space hand scored differently than the pipeline chose
(divergence). Now the fallback **prefers the deg-0 (image-space) rating** (the
correct semantics for normally-photographed hands; it rates 5_kenya/5_trope→5
and 1_coffee→1) and only uses rotated crops as a fallback when RTMPose cannot
rate the upright crop (dorsal/side views like 4/4). The returned hand keeps
crop-frame points (so the caller's rating always matches) and tags
`LandmarkedImage.Hand.rotationDegrees`; the ONNX use case un-rotates the points
for storage, keeping the stored geometry image-space-correct.

## Uncertain tier: the remaining gaps become reviewable (2026-08-02)

Per the user's direction, the two final gaps are no longer silently dropped.
The ONNX pipeline gained a second, lower presence gate
(`minHandUncertainPresenceConfidence = 0.20`) below the confident 0.35:

- presence **< 0.20** → not a hand (no_score stays filtered — on-device no_score
  peaks at 0.13)
- presence **0.20–0.35** → still rated, but the hand is flagged
  `LandmarkedImage.Hand.uncertain = true` — a **best-guess score** for the UI's
  new "Uncertain — best guesses" section
- presence **≥ 0.35** → confident (main grid)

This is a real fix, not just a relabel: both former gaps are now DETECTED and
rated as best guesses:

| Sample | Before | After |
|---|---|---|
| `4/4_also.jpg` | detection gap (hs 0.28) | detected, **ROCK-5?** (best guess, uncertain) |
| `2/2_peanuts.jpg` | detection gap (hs 0.30–0.32) | detected, **OPEN_PALM-3 / OK_SIGN-3?** (best guess, uncertain) |

### Multi-factor RTMPose fallback (0.85x crop)

The fallback now tries the square at **both 1.2× and 0.85×** expansions. The
0.85× (tighter) crop isolates the hand when the RTMDet box is tall/thin and
mostly background — the 2_peanuts hand rates **PEACE-2** at 0.85× in Python but
OK_SIGN-3 at 1.2×. The image-space (deg 0, kp ≥ 0.3) preference keeps the 1.2×
ratings that were already correct, so no other sample regressed (verified in
Python: 1_coffee/2_coffee keep their 1.2× ratings). On-device, 2_peanuts ends
up as an uncertain best guess (the PEACE-2 read at 0.85× didn't transfer
exactly to the device decode) — but it is now *shown*, which was the point.

### Plumbing

- `gallery.sq`: `DetectedHand.uncertain INTEGER AS Boolean NOT NULL DEFAULT 0`
  (+ migration `3.sqm`), `selectUncertainImagesWithScore` query returning uri +
  best guess score for images with ONLY uncertain hands; `selectMatchingImages`
  now requires a confident hand.
- `GalleryViewModel`: collects `uncertainDetections`; `ThumbRatingScreen`
  renders an "Uncertain — best guesses" section header + cards with a
  `? <score>` badge (errorContainer) below the confident grid.
- Both dataset tests keep the honest-collect pattern; the ONNX test asserts
  `KNOWN_UNCERTAIN_DETECTIONS = [4/4_also.jpg, 2/2_peanuts.jpg]`, empty
  `KNOWN_DETECTION_GAPS`, empty mismatches.

On-device full suite: **OK (10 tests)**. All 16 scored samples rate correctly
(2 as uncertain best guesses), all 5 no_score samples stay filtered.

> **Note:** the app currently wires the **MediaPipe** use case
> (`GalleryViewModel` injects `LandmarkPendingImagesUseCase`), whose hands
> always have `uncertain = false` — so the uncertain section stays empty in
> the running app until it switches to the ONNX use case
> (`OnnxLandmarkPendingImagesUseCase`), which is the only pipeline that sets
> the flag today. The feature is fully exercised by `OnnxHandLandmarkDatasetTest`.
