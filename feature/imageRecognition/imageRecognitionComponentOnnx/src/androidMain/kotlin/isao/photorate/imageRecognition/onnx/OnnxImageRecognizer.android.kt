package isao.photorate.imageRecognition.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import isao.photorate.imageRecognition.GestureResult
import isao.photorate.imageRecognition.feature.HandFeatures
import isao.photorate.imageRecognition.gesture.Gesture
import isao.photorate.imageRecognition.gesture.ThumbOnlyGesture
import isao.photorate.imageRecognition.gesture.ThumbSignal
import isao.photorate.imageRecognition.landmark.HandLandmarker
import isao.photorate.imageRecognition.landmark.HandLandmarkerFactory
import isao.photorate.imageRecognition.landmark.HandLandmarkerOptions
import isao.photorate.imageRecognition.landmark.LandmarkCandidate
import isao.photorate.imageRecognition.landmark.LandmarkedImage
import isao.photorate.imageRecognition.onnx.AndroidOnnxHandLandmarker.Companion.NMS_MAX_OUT
import isao.photorate.imageRecognition.recognizer.GestureRecognizer
import isao.photorate.imageRecognition.recognizer.tryRecognizers
import java.io.File
import java.nio.FloatBuffer
import java.util.EnumSet
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.system.measureTimeMillis

/**
 * Android factory for the ONNX hand pipeline: RTMDet hand detection -> multi-rotation RTMPose
 * search -> gesture rating.
 */
class AndroidOnnxHandLandmarkerFactory
@Inject
constructor(
  private val context: Context,
  /** null = plain CPU (the app default). */
  private val nnapiFlags: Set<NNAPIFlags>? = null,
) : HandLandmarkerFactory {
  override fun createFromOptions(options: HandLandmarkerOptions): HandLandmarker {
    val landmarker: AndroidOnnxHandLandmarker
    measureTimeMillis {
      landmarker = AndroidOnnxHandLandmarker(context, options, nnapiFlags)
    }
      .also {
        Log.d(
          TAG,
          "Initialized ONNX hand pipeline in $it ms (provider=${if (nnapiFlags != null) "NNAPI" else "CPU"})",
        )
      }
    return landmarker
  }

  companion object {
    private const val TAG = "OnnxHandLandmarker"

    /**
     * Parses a comma-separated [NNAPIFlags] string (e.g. "USE_FP16,CPU_DISABLED") for the
     * instrumented benchmark: blank or "default" = plain addNnapi() defaults, null input = plain
     * CPU. Lets the same pipeline run under any provider without the app depending on ORT types
     * (used by OnnxHandLandmarkDatasetTest via the `nnapiFlags` instrumentation argument).
     */
    fun parseNnapiFlags(raw: String?): Set<NNAPIFlags>? {
      val trimmed = raw?.trim().orEmpty()
      if (trimmed.isEmpty()) return null
      if (trimmed.equals("default", ignoreCase = true)) return emptySet()
      return trimmed.split(',').map { NNAPIFlags.valueOf(it.trim()) }.toSet()
    }

    // Confirmed-source mmdeploy exports (ml/original_models), GPU-ready
    // variants: float32-only graphs (NNAPI-eligible). The RTMDet is the
    // original weights with the baked NMS + redundant sort tail cut
    // (caller-side NMS in detectBoxes, params from the original export);
    // the RTMPose is the raw SimCC export with the int64 shape bookkeeping
    // constant-folded away (decode in rtmposeLandmarks). See
    // ml/original_models/GPU_READY_README.md. Production stays on these
    // f32clean assets: the INT8 QDQ experiment (plans/model_zoo_pinto/
    // quant_int8_documented.py) collapsed detector scores below the 0.25
    // gate (15/31 detection gaps) and the darwinn TPU rejected even the
    // preserved-QDQ graph (see docs/MODEL_ZOO_EXPLORATION.md).
    const val DETECTOR_ASSET = "rtmdet_nano_8xb32-300e_hand-267f9c8f/end2end_f32clean.onnx"
    const val RTMPOSE_ASSET =
      "rtmpose-m_simcc-hand5_pt-aic-coco_210e-256x256-74fb594_20230320/end2end_f32clean.onnx"
  }
}

/**
 * Android ONNX pipeline: RTMDet letterbox -> multi-rotation RTMPose search -> gesture classifier
 * (the gate). Verified on plans/samples with onnxruntime 1.28.0 — the same version the Android AAR
 * ships.
 */
class AndroidOnnxHandLandmarker
internal constructor(
  context: Context,
  private val options: HandLandmarkerOptions,
  private val nnapiFlags: Set<NNAPIFlags>?,
) : HandLandmarker {

  private val ortEnv = OrtEnvironment.getEnvironment()
  private val detectorSession: OrtSession
  private val rtmposeSession: OrtSession
  private val defaultRecognizers = OnnxGestureRecognizerProvider().createRecognizers()

  init {
    val detectorFile = copyAssetToFile(context, AndroidOnnxHandLandmarkerFactory.DETECTOR_ASSET)
    val rtmposeFile = copyAssetToFile(context, AndroidOnnxHandLandmarkerFactory.RTMPOSE_ASSET)
    // Execution provider: plain CPU by default; NNAPI when nnapiFlags != null.
    // The ORIGINAL exports were NNAPI-poisoned (int64 shape bookkeeping in
    // RTMPose, baked NMS in RTMDet) — the darwinn driver rejects non-float32
    // operands, every NNAPI config landed on the slower nnapi-reference CPU
    // backend (65.6s / 55.0s vs 43.6s full scan, 2026-08-04), and
    // CPU_DISABLED hard-failed session creation (0/38 ops supported). The
    // *f32clean assets now in use are float32-only graphs (NMS and SimCC
    // decode moved caller-side), so NNAPI is eligible again — re-measured
    // on-device; see docs/MODEL_ZOO_EXPLORATION.md for the verdict.
    // use {} so the native SessionOptions handle closes even when
    // createSession throws (CPU_DISABLED hard-fails on this device).
    OrtSession.SessionOptions().use { sessionOptions ->
      if (nnapiFlags != null) {
        // NNAPI needs the QDQ graph intact: ORT's optimizer rewrites
        // QDQ patterns into int8 ops (ConvInteger/MatMulInteger) the
        // NNAPI EP can't partition — observed on-device as 4/950
        // nodes vs 1641/1645 in the mobile-usability checker. NO_OPT
        // alone wasn't enough (the QDQ fusion runs regardless); the
        // documented config keys (kOrtSessionOptionsDisableQuantQDQ /
        // DisableDoubleQDQRemover, ORT 1.28 header) keep the raw
        // Q->DQ units for the NNAPI EP to see. Config entries first:
        // the ORT header asks for options to be set prior to
        // appending the EP.
        sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
        sessionOptions.addConfigEntry("session.disable_quant_qdq", "1")
        // Defensive: these models have single Q->DQ pairs, but the
        // key stops ORT removing Q->(DQ->Q)->DQ chains if one appears.
        sessionOptions.addConfigEntry("session.disable_double_qdq_remover", "1")
        if (nnapiFlags.isEmpty()) {
          sessionOptions.addNnapi()
        } else {
          sessionOptions.addNnapi(EnumSet.copyOf(nnapiFlags))
        }
      }
      detectorSession = ortEnv.createSession(detectorFile.absolutePath, sessionOptions)
      rtmposeSession = ortEnv.createSession(rtmposeFile.absolutePath, sessionOptions)
    }
  }

  override fun detect(candidate: LandmarkCandidate): LandmarkedImage {
    var detectedIn = 0L
    val hands: List<LandmarkedImage.Hand>
    measureTimeMillis {
      hands = runPipeline(candidate)
    }
      .also { detectedIn = it }
    return LandmarkedImage(hands = hands, detectedInMs = detectedIn)
  }

  /**
   * Detects hands and classifies each using [recognizers]. Returns one [GestureResult] per detected
   * hand. Hands where no recognizer matches are omitted.
   */
  override fun detectWithRecognizers(
    candidate: LandmarkCandidate,
    recognizers: List<GestureRecognizer<*>>,
  ): List<GestureResult> {
    val landmarked = detect(candidate)
    val results = ArrayList<GestureResult>()
    for (hand in landmarked.hands) {
      val features = HandFeatures(hand)
      val recognized = tryRecognizers(features, recognizers)
      if (recognized != null && recognized.first.score != null) {
        results.add(GestureResult(recognized.first, recognized.second, hand))
      }
    }
    return results
  }

  // --- Pipeline: RTMDet boxes -> multi-rot RTMPose search -> gesture rate ---

  private fun runPipeline(bitmap: LandmarkCandidate): List<LandmarkedImage.Hand> {
    val boxes = detectBoxes(bitmap) // pixel coords in the original image
    val hands = ArrayList<LandmarkedImage.Hand>()
    for ((box, score) in boxes.take(options.maxNumHands)) {
      if (score < options.minHandDetectionConfidence) continue
      rtmposeRating(bitmap, box)?.let { hands.add(it) }
    }
    // Edge fallback: a hand mostly out of frame at the left/right image
    // edge is invisible to full-frame RTMDet (5_kimbo — the thumb is in
    // shot, the rest off-frame). Probe the edge strips for a confident
    // thumb chain and rate it as a low-certainty THUMBS guess
    // (edgeDetected hands take the classifier's thumb-only path).
    if (hands.isEmpty()) {
      val hint = boxes.firstOrNull()?.second ?: 0f
      hands.addAll(edgeThumbFallback(bitmap, hint).take(options.maxNumHands))
    }
    return hands
  }

  // --- Detection stage: RTMDet letterbox (320x320, top-left pad, mean/std) ---

  private fun detectBoxes(bitmap: Bitmap): List<Pair<IntArray, Float>> {
    val iw = bitmap.width
    val ih = bitmap.height
    val ratio = min(DETECTOR_SIZE / iw.toFloat(), DETECTOR_SIZE / ih.toFloat())
    val nw = max((iw * ratio).toInt(), 1)
    val nh = max((ih * ratio).toInt(), 1)
    val resized = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
    val padded = Bitmap.createBitmap(DETECTOR_SIZE, DETECTOR_SIZE, Bitmap.Config.ARGB_8888)
    Canvas(padded).drawBitmap(resized, 0f, 0f, Paint())
    val input = bitmapToCHWMeanStd(padded, rgbOrder = true)
    val shape = longArrayOf(1, 3, DETECTOR_SIZE.toLong(), DETECTOR_SIZE.toLong())

    // The f32clean export has no baked NMS: raw anchors boxes [1,2100,4]
    // + scores [1,2100,1] in 320-space (top-left padded). Post-processing
    // (score filter + NMS) happens here with the baked export's params.
    val (boxesRaw, scoresRaw) =
      OnnxTensor.createTensor(ortEnv, input, shape).use { tensor ->
        detectorSession.run(mapOf("input" to tensor)).use { result ->
          (result.get(0) as OnnxTensor).floatBuffer.takeFloatArray() to
            (result.get(1) as OnnxTensor).floatBuffer.takeFloatArray()
        }
      }
    val keep = nms(boxesRaw, scoresRaw)
    val boxes = ArrayList<Pair<IntArray, Float>>(keep.size)
    for (i in keep) {
      val o = i * 4
      val score = scoresRaw[i]
      val bx0 = max((boxesRaw[o] / ratio).toInt(), 0)
      val by0 = max((boxesRaw[o + 1] / ratio).toInt(), 0)
      val bx1 = min((boxesRaw[o + 2] / ratio).toInt(), iw)
      val by1 = min((boxesRaw[o + 3] / ratio).toInt(), ih)
      if (bx1 - bx0 < MIN_BOX_SIDE || by1 - by0 < MIN_BOX_SIDE) continue
      boxes.add(intArrayOf(bx0, by0, bx1, by1) to score)
    }
    boxes.sortByDescending { it.second }
    return boxes
  }

  /**
   * Port of the baked NonMaxSuppression (params read from the original export's initializers):
   * filter by score, then suppress overlapping boxes in descending-score order, keep at most
   * [NMS_MAX_OUT]. Returns indices into the raw anchor arrays. The early `break` on a sub-
   * threshold score is valid only because [order] is sorted descending — keep it that way if
   * refactoring.
   */
  private fun nms(boxes: FloatArray, scores: FloatArray): List<Int> {
    val order = (scores.indices).sortedByDescending { scores[it] }
    val keep = ArrayList<Int>()
    for (i in order) {
      if (scores[i] < NMS_SCORE_THR) break
      if (keep.size >= NMS_MAX_OUT) break
      var suppressed = false
      val ix1 = boxes[i * 4]
      val iy1 = boxes[i * 4 + 1]
      val ix2 = boxes[i * 4 + 2]
      val iy2 = boxes[i * 4 + 3]
      for (j in keep) {
        val xx1 = max(ix1, boxes[j * 4])
        val yy1 = max(iy1, boxes[j * 4 + 1])
        val xx2 = min(ix2, boxes[j * 4 + 2])
        val yy2 = min(iy2, boxes[j * 4 + 3])
        val w = max(xx2 - xx1, 0f)
        val h = max(yy2 - yy1, 0f)
        val inter = w * h
        val areaI = (ix2 - ix1) * (iy2 - iy1)
        val areaJ = (boxes[j * 4 + 2] - boxes[j * 4]) * (boxes[j * 4 + 3] - boxes[j * 4 + 1])
        val iou = inter / (areaI + areaJ - inter + 1e-9f)
        if (iou > NMS_IOU_THR) {
          suppressed = true
          break
        }
      }
      if (!suppressed) keep.add(i)
    }
    return keep
  }

  // --- Landmark stage: multi-rotation RTMPose search on the expanded box ---

  /**
   * Runs RTMPose hand on the expanded square box at rotations {0, 90, 180, 270} and returns the
   * best hand. The rotation search prefers a rotation that forms a recognized gesture; if NO
   * rotation does, the highest- confidence hand is still returned so the scan's rater can decide
   * (normally it rejects it; the dev-mode best-guess rater guesses). The only hard gate here is
   * [HandLandmarkerOptions.minHandKpConfidence] — below it a box is not a hand at all (no_score
   * stays filtered).
   *
   * The image-space (deg 0) rating is the ground truth for normally-photographed hands — it
   * correctly rates thumbs-up (5_kenya, 5_trope), thumbs-down (1_coffee) and the OK signs. Rotated
   * crops are only a fallback for hands RTMPose cannot rate upright (dorsal/side views like 4/4,
   * which reads THUMBS-4 at 90°).
   *
   * The crop is tried at two expansions (1.2x and 0.85x): the 0.85x crop isolates the hand when the
   * detected box is tall/thin and mostly background, while the image-space-preference rule keeps
   * the 1.2x ratings that are already correct. Verified on plans/samples (see
   * plans/benchmarks/rtmpose_only_v5.py).
   *
   * The returned hand keeps the points in IMAGE-PIXEL space (crop-space keypoints offset by the
   * crop origin). This is an isometry of the crop frame the classifier's thresholds were tuned in
   * (the Python benchmark classifies raw crop pixels), so the gesture distances/angles are
   * undistorted regardless of image aspect ratio — image-space normalization (x/iw, y/ih) would
   * anisotropically squash the geometry on non-square images and reject valid hands (verified:
   * 3_ok_sign_peanuts fails normalized but rates OK in pixels). Storage code un-rotates via
   * [LandmarkedImage.Hand.rotationDegrees] and normalizes to 0..1. Confidence tiers are
   * keypoint-based: below [HandLandmarkerOptions.minHandKpConfidence] a box is not a hand at all;
   * ROCK/OK below [HandLandmarkerOptions.minHandConfidentKpConfidence] are still rated but flagged
   * uncertain (best guess) — tuned so low-confidence ROCK/OK reads (holding-in-two-hands at kp
   * 0.28) filter while real ones (kp >= 0.58) stay confident.
   */
  private fun rtmposeRating(bitmap: Bitmap, box: IntArray): LandmarkedImage.Hand? {
    val iw = bitmap.width
    val ih = bitmap.height
    val candidates = CANDIDATE_ROTATIONS

    var imageSpace: HandWithScore? = null
    var bestRotated: HandWithScore? = null
    // Highest-confidence hand across ALL rotations, including those that
    // form no gesture: the dev-mode best-guess rater still rates those, so
    // the pipeline must not drop them here.
    var bestKp: HandWithScore? = null
    search@ for (factor in FALLBACK_FACTORS) {
      val square = expandSquare(box, iw, ih, factor)
      val cx = (square[0] + square[2]) / 2f
      val cy = (square[1] + square[3]) / 2f
      val side = (square[2] - square[0]).toFloat()
      for (deg in candidates) {
        val (crop, _) = rotateAndCropRectangle(bitmap, cx, cy, side, side, deg) ?: continue
        val kps = rtmposeLandmarks(crop) ?: continue
        val points = ArrayList<LandmarkedImage.Point>(NUM_LANDMARKS)
        var kpSum = 0f
        for (i in 0 until NUM_LANDMARKS) {
          points.add(
            LandmarkedImage.Point(
              // Image-pixel space (crop origin + crop-space
              // keypoint): isotropic, so the classifier's
              // distance ratios/angles are undistorted regardless
              // of the image aspect ratio (see above).
              x = square[0] + kps[i * 3],
              y = square[1] + kps[i * 3 + 1],
              z = kps[i * 3 + 2],
            )
          )
          kpSum += kps[i * 3 + 2]
        }
        val hand = LandmarkedImage.Hand(points, rotationDegrees = deg)
        val kpMean = kpSum / NUM_LANDMARKS
        val features = HandFeatures(hand)
        val recognized = tryRecognizers(features, defaultRecognizers)
        val gesture = recognized?.first
        val result = HandWithScore(hand, gesture, kpMean)
        if (bestKp == null || result.kpMean > bestKp.kpMean) bestKp = result
        if (gesture == null) continue
        if (deg == 0f) {
          if (imageSpace == null || result.kpMean > imageSpace.kpMean) imageSpace = result
          // Early exit: a confident upright read on the standard
          // 1.2x crop is the guaranteed winner (image-space
          // preference below), so the rotated sweep and the 0.85x
          // pass cannot change the outcome — skip them. Verified in
          // plans/benchmarks/rot_analysis.py: identical winners on
          // all 33 samples, RTMPose runs 204 -> 123 (-40%). The
          // genuinely slow images (2_coffee, 2_peanuts, the
          // no_score_holding_* set) have low deg-0 confidence and
          // still take the full search.
          if (factor == BOX_EXPANSION && imageSpace.kpMean >= MIN_RTMPOSE_KP) {
            break@search
          }
          continue
        }
        val better =
          bestRotated == null ||
            run {
              val currentGesture = bestRotated.gesture ?: return@run true
              val isThumbsUp = gesture is ThumbSignal || gesture is ThumbOnlyGesture
              val currentIsThumbsUp =
                currentGesture is ThumbSignal || currentGesture is ThumbOnlyGesture
              when {
                // A THUMBS beats any other gesture (the fallback exists to rate
                // the thumbs-up geometry the rotated crops see correctly).
                isThumbsUp && !currentIsThumbsUp -> true
                !isThumbsUp && currentIsThumbsUp -> false
                // Among THUMBS pick the highest score; tie-break on confidence.
                isThumbsUp -> {
                  val score = gesture?.score?.score ?: 0
                  val currentScore = currentGesture.score?.score ?: 0
                  score > currentScore ||
                    (score == currentScore && result.kpMean > bestRotated.kpMean)
                }
                // Non-THUMBS gestures: trust the most confident one.
                else -> result.kpMean > bestRotated.kpMean
              }
            }
        if (better) bestRotated = result
      }
    }
    // Prefer the image-space rating, but only when RTMPose is reasonably
    // confident in those keypoints; a low-confidence upright crop can mis-
    // rate, and the rotated search then has better data to work with. When
    // no rotation forms a gesture at all, fall back to the highest-
    // confidence hand (the dev-mode rater can still guess from it).
    val winner =
      when {
        imageSpace != null && imageSpace.kpMean >= MIN_RTMPOSE_KP -> imageSpace
        // don't pick the winner that will fail the confidence check
        bestRotated.takeIf { (it?.kpMean ?: 0f) >= options.minHandKpConfidence } != null ->
          bestRotated

        imageSpace.takeIf { (it?.kpMean ?: 0f) >= options.minHandKpConfidence } != null ->
          imageSpace

        bestKp.takeIf { (it?.kpMean ?: 0f) >= options.minHandKpConfidence } != null -> bestKp
        else -> null
      } ?: return null

    // Confidence tiers replace the sparse presence gate. Below the floor
    // the box is not a hand at all (no_score stays filtered); ROCK/OK need
    // higher confidence than THUMBS (a curled-finger read at low kp is
    // usually a holding/gripping false positive). Hands with no recognized
    // gesture use the non-THUMBS gate (the dev-mode rater marks its guess
    // uncertain regardless).
    if (winner.kpMean < options.minHandKpConfidence) return null
    val isThumbsUp = winner.gesture is ThumbSignal || winner.gesture is ThumbOnlyGesture
    val confidentKp =
      if (isThumbsUp) options.minHandKpConfidence else options.minHandConfidentKpConfidence
    return winner.hand.copy(uncertain = winner.kpMean < confidentKp)
  }

  // --- Edge thumb-only fallback (partial hands mostly out of frame) -------

  /**
   * Probes the left and right edge strips for a partial hand whose thumb chain is confidently
   * pointing up. At most one hand per edge. Only runs when the main pass found nothing (see
   * [runPipeline]). The gates live in [HandGestureClassifier] (EDGE_* constants) and are reached by
   * classifying each read with edgeDetected = true — the strip detector must fire above the
   * detection gate and the read above the kp floor.
   */
  private fun edgeThumbFallback(bitmap: Bitmap, fullFrameBest: Float): List<LandmarkedImage.Hand> {
    // The full-frame detector already ran for the main pass; an edge hand
    // still leaves a weak full-frame response (5_kimbo: 0.167), so below
    // the hint threshold the image has no hand signal at all — skip.
    if (fullFrameBest < EDGE_HINT_DET) return emptyList()
    val iw = bitmap.width
    val ih = bitmap.height
    val stripW = max((iw * EDGE_STRIP_FRACTION).toInt(), 1)
    val hands = ArrayList<LandmarkedImage.Hand>()
    for (side in 0..1) {
      val x0 = if (side == 0) 0 else iw - stripW
      for ((fb, ft) in EDGE_BANDS) {
        val y0 = (ih * fb).toInt()
        val y1 = (ih * ft).toInt()
        val strip = padStripToSquare(bitmap, x0, y0, stripW, y1 - y0)
        val boxes = detectBoxes(strip)
        if (boxes.isEmpty() || boxes.first().second < options.minHandDetectionConfidence) {
          continue
        }
        val (box, _) = boxes.first()
        val imBox = intArrayOf(x0 + box[0], y0 + box[1], x0 + box[2], y0 + box[3])
        edgeThumbScan(bitmap, imBox)?.let {
          hands.add(it)
          break
        }
      }
    }
    return hands
  }

  /**
   * Runs RTMPose on an edge-hugging box at all rotations and returns the best read whose thumb
   * chain qualifies as a partial-hand thumbs-up (the classifier's thumb-only path), or null. The
   * hand is marked uncertain (the off-frame fingers are unreliable — the score is a best guess).
   */
  private fun edgeThumbScan(bitmap: Bitmap, box: IntArray): LandmarkedImage.Hand? {
    val iw = bitmap.width
    val ih = bitmap.height
    val square = expandSquare(box, iw, ih, BOX_EXPANSION)
    val cx = (square[0] + square[2]) / 2f
    val cy = (square[1] + square[3]) / 2f
    val side = (square[2] - square[0]).toFloat()
    var best: LandmarkedImage.Hand? = null
    var bestThumbConf = 0f
    var bestKpMean = 0f
    for (deg in CANDIDATE_ROTATIONS) {
      val (crop, _) = rotateAndCropRectangle(bitmap, cx, cy, side, side, deg) ?: continue
      val kps = rtmposeLandmarks(crop) ?: continue
      val points = ArrayList<LandmarkedImage.Point>(NUM_LANDMARKS)
      var kpSum = 0f
      var thumbSum = 0f
      for (i in 0 until NUM_LANDMARKS) {
        points.add(
          LandmarkedImage.Point(
            x = square[0] + kps[i * 3],
            y = square[1] + kps[i * 3 + 1],
            z = kps[i * 3 + 2],
          )
        )
        kpSum += kps[i * 3 + 2]
        if (i in 1..4) thumbSum += kps[i * 3 + 2]
      }
      val hand =
        LandmarkedImage.Hand(
          points,
          rotationDegrees = deg,
          edgeDetected = true,
        )
      val features = HandFeatures(hand)
      val recognized = tryRecognizers(features, defaultRecognizers)
      val isThumbsUp = recognized?.first is ThumbSignal || recognized?.first is ThumbOnlyGesture
      if (!isThumbsUp) {
        continue
      }
      val kpMean = kpSum / NUM_LANDMARKS
      val thumbConf = thumbSum / 4f
      // Prefer the most confident thumb chain, then kp mean (mirrors the
      // Python edge_thumb_scan selection).
      if (
        best == null ||
          thumbConf > bestThumbConf ||
          (thumbConf == bestThumbConf && kpMean > bestKpMean)
      ) {
        best = hand
        bestThumbConf = thumbConf
        bestKpMean = kpMean
      }
      // Deg-0-first acceptance: the upright read is the only
      // gate-qualifying rotation on the edge sample (5_kimbo — deg 0
      // passes the thumb-chain gates at angle 22.4°, 90/180/270 fail),
      // so accept it immediately and skip the rotated sweep. The sweep
      // still runs when deg 0 fails the gates (a sideways thumb can
      // still qualify rotated). Prunes 4 RTMPose runs to 1 per strip.
      if (deg == 0f) break
    }
    return best?.copy(uncertain = true)
  }

  /** Crops [x0, y0, w, h] from the image and pads it to a square with gray. */
  private fun padStripToSquare(image: Bitmap, x0: Int, y0: Int, w: Int, h: Int): Bitmap {
    val crop = Bitmap.createBitmap(image, x0, y0, w, h)
    val side = max(w, h)
    val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(Color.rgb(114, 114, 114))
    canvas.drawBitmap(crop, 0f, 0f, Paint())
    return out
  }

  private data class HandWithScore(
    val hand: LandmarkedImage.Hand,
    val gesture: Gesture?,
    val kpMean: Float,
  )

  /**
   * RTMPose hand (original mmdeploy export): top-down affine to 256x256, BGR-order input normalized
   * with RGB mean/std (matches the verified Python). The original end2end.onnx has NO baked SimCC
   * decode (the old modified export had one): it outputs raw heatmaps simcc_x/simcc_y [1, 21, 512],
   * so decode = argmax / simcc_split_ratio (2.0) mapped back through the inverse affine to crop
   * pixels, with min(peak_x, peak_y) as confidence. Verified bit-exact against the old decode-baked
   * export on plans/samples (keypoint diff 0.0000, score diff 0.00000).
   */
  private fun rtmposeLandmarks(crop: Bitmap): FloatArray? {
    val w = crop.width
    val h = crop.height
    val warped = topDownAffine(crop)
    val input = bitmapToCHWMeanStd(warped, rgbOrder = false) // model expects BGR
    val shape = longArrayOf(1, 3, RTMPOSE_SIZE.toLong(), RTMPOSE_SIZE.toLong())

    val (simccX, simccY) =
      OnnxTensor.createTensor(ortEnv, input, shape).use { tensor ->
        rtmposeSession.run(mapOf("input" to tensor)).use { result ->
          (result.get(0) as OnnxTensor).floatBuffer.takeFloatArray() to
            (result.get(1) as OnnxTensor).floatBuffer.takeFloatArray()
        }
      }

    val out = FloatArray(NUM_LANDMARKS * 3)
    // Inverse of the top-down affine (see topDownAffine): undo the scale
    // about the crop center to map decoded 256-space bins to crop pixels.
    val bboxW = 1.25f * w
    val bboxH = 1.25f * h
    val scale = RTMPOSE_SIZE / max(bboxH * 0.75f, bboxW)
    val cx = w / 2f
    val cy = h / 2f
    for (i in 0 until NUM_LANDMARKS) {
      var xi = 0
      var yi = 0
      var xv = simccX[i * SIMCC_SIZE]
      var yv = simccY[i * SIMCC_SIZE]
      for (b in 1 until SIMCC_SIZE) {
        val vx = simccX[i * SIMCC_SIZE + b]
        val vy = simccY[i * SIMCC_SIZE + b]
        if (vx > xv) {
          xv = vx
          xi = b
        }
        if (vy > yv) {
          yv = vy
          yi = b
        }
      }
      out[i * 3] = (xi / SIMCC_SPLIT_RATIO - RTMPOSE_SIZE / 2f) / scale + cx
      out[i * 3 + 1] = (yi / SIMCC_SPLIT_RATIO - RTMPOSE_SIZE / 2f) / scale + cy
      // Confidence: min of the two heatmap peaks (the baked decode's
      // score is exactly this).
      out[i * 3 + 2] = min(xv, yv)
    }
    return out
  }

  /**
   * Port of the mmpose top-down affine for rotation 0 (see plans/benchmarks/landmark_stage.md):
   * scale the crop about its center so the max(0.75h, 1.25w)-scaled box fills 256x256, black-filled
   * borders.
   */
  private fun topDownAffine(crop: Bitmap): Bitmap {
    val w = crop.width.toFloat()
    val h = crop.height.toFloat()
    val bboxW = 1.25f * w
    val bboxH = 1.25f * h
    val wScaled = max(bboxH * 0.75f, bboxW)
    val scale = RTMPOSE_SIZE / wScaled
    val cx = w / 2f
    val cy = h / 2f
    val matrix = Matrix()
    matrix.postScale(scale, scale, cx, cy)
    matrix.postTranslate(RTMPOSE_SIZE / 2f - cx, RTMPOSE_SIZE / 2f - cy)
    val out = Bitmap.createBitmap(RTMPOSE_SIZE, RTMPOSE_SIZE, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(Color.BLACK)
    canvas.drawBitmap(crop, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
    return out
  }

  /** Expands a box around its center and makes it square (UNCLAMPED). */
  private fun expandSquare(
    box: IntArray,
    iw: Int,
    ih: Int,
    factor: Float = BOX_EXPANSION,
  ): IntArray {
    val (x1, y1, x2, y2) = box
    val cx = (x1 + x2) / 2f
    val cy = (y1 + y2) / 2f
    val w = (x2 - x1) * factor
    val h = (y2 - y1) * factor
    val side = max(w, h)
    return intArrayOf(
      (cx - side / 2).toInt(),
      (cy - side / 2).toInt(),
      (cx + side / 2).toInt(),
      (cy + side / 2).toInt(),
    )
  }

  // --- Bitmap helpers (port of the verified Python preprocessing) ---

  private fun rotateAndCropRectangle(
    image: Bitmap,
    cx: Float,
    cy: Float,
    width: Float,
    height: Float,
    degree: Float,
  ): Pair<Bitmap, FloatArray>? {
    val ih = image.height
    val iw = image.width
    val size = (sqrt((iw * iw + ih * ih).toDouble()).toInt() + 2) * 2
    val padded = padImage(image, size, size)
    val cxP = cx + abs(size - iw) / 2f
    val cyP = cy + abs(size - ih) / 2f

    val bb = boundingBoxFromRotatedRect(cxP, cyP, width, height, degree)
    val crop = cropRect(padded, bb[0], bb[1], bb[2], bb[3]) ?: return null
    val wDiff = (crop.width - width.toInt() + 1).toFloat()
    val hDiff = (crop.height - height.toInt() + 1).toFloat()

    val rotated = imageRotationWithoutCrop(crop, degree)
    val ccx = rotated.width / 2
    val ccy = rotated.height / 2
    val rw = width.toInt()
    val rh = height.toInt()
    // cropRect expects the CENTER (it subtracts half the size internally), so
    // pass ccx/ccy directly — passing ccx - rw/2 double-subtracted and made
    // every tall hand crop out-of-bounds (y0 < 0), killing all detections.
    val final = cropRect(rotated, ccx, ccy, rw, rh) ?: return null
    return final to floatArrayOf(wDiff, hDiff)
  }

  private fun padImage(image: Bitmap, tw: Int, th: Int): Bitmap {
    val padded = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
    val startH = th / 2 - image.height / 2
    val startW = tw / 2 - image.width / 2
    Canvas(padded).drawBitmap(image, startW.toFloat(), startH.toFloat(), Paint())
    return padded
  }

  private fun cropRect(image: Bitmap, cx: Int, cy: Int, width: Int, height: Int): Bitmap? {
    val x0 = cx - width / 2
    val y0 = cy - height / 2
    if (x0 < 0 || y0 < 0 || x0 + width > image.width || y0 + height > image.height) return null
    return Bitmap.createBitmap(image, x0, y0, width, height)
  }

  /** Upright bounding box of a rotated rect: [cx, cy, width, height] in pixels. */
  private fun boundingBoxFromRotatedRect(
    cx: Float,
    cy: Float,
    w: Float,
    h: Float,
    degree: Float,
  ): IntArray {
    val theta = Math.toRadians(degree.toDouble())
    val cosT = cos(theta)
    val sinT = sin(theta)
    val hw = w / 2f
    val hh = h / 2f
    // Four corners of the rotated rect (cv2.boxPoints equivalent).
    val xs = DoubleArray(4)
    val ys = DoubleArray(4)
    xs[0] = cx + hw * cosT - hh * sinT
    ys[0] = cy + hw * sinT + hh * cosT
    xs[1] = cx - hw * cosT - hh * sinT
    ys[1] = cy - hw * sinT + hh * cosT
    xs[2] = cx - hw * cosT + hh * sinT
    ys[2] = cy - hw * sinT - hh * cosT
    xs[3] = cx + hw * cosT + hh * sinT
    ys[3] = cy + hw * sinT - hh * cosT
    val minX = floor(xs.min()).toInt()
    val maxX = floor(xs.max()).toInt() + 1
    val minY = floor(ys.min()).toInt()
    val maxY = floor(ys.max()).toInt() + 1
    val cxx = (minX + maxX) / 2
    val cyy = (minY + maxY) / 2
    return intArrayOf(cxx, cyy, maxX - minX, maxY - minY)
  }

  /** Rotates the image about its center without cropping (expands the canvas). */
  private fun imageRotationWithoutCrop(image: Bitmap, degree: Float): Bitmap {
    val w = image.width
    val h = image.height
    val theta = Math.toRadians(degree.toDouble())
    val absCos = abs(cos(theta))
    val absSin = abs(sin(theta))
    val boundW = (h * absSin + w * absCos).toInt()
    val boundH = (h * absCos + w * absSin).toInt()
    val matrix = Matrix()
    matrix.postRotate(-degree, w / 2f, h / 2f)
    matrix.postTranslate(boundW / 2f - w / 2f, boundH / 2f - h / 2f)
    val out = Bitmap.createBitmap(boundW, boundH, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(image, matrix, Paint())
    return out
  }

  /**
   * Mean/std CHW for the RTMDet detector (RGB order) and RTMPose (BGR order). Mean/std are the
   * mmdet RGB values (123.675, 116.28, 103.53); RTMPose receives BGR-order pixels normalized with
   * those same stats — exactly the verified Python preprocessing.
   */
  private fun bitmapToCHWMeanStd(bitmap: Bitmap, rgbOrder: Boolean): FloatBuffer {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val stride = w * h
    val buffer = FloatBuffer.allocate(3 * stride)
    val c0 = FloatArray(stride)
    val c1 = FloatArray(stride)
    val c2 = FloatArray(stride)
    for (i in 0 until stride) {
      val pixel = pixels[i]
      val r = (pixel shr 16 and 0xFF).toFloat()
      val g = (pixel shr 8 and 0xFF).toFloat()
      val b = (pixel and 0xFF).toFloat()
      if (rgbOrder) {
        c0[i] = (r - MEAN_RGB[0]) / STD_RGB[0]
        c1[i] = (g - MEAN_RGB[1]) / STD_RGB[1]
        c2[i] = (b - MEAN_RGB[2]) / STD_RGB[2]
      } else {
        c0[i] = (b - MEAN_RGB[0]) / STD_RGB[0]
        c1[i] = (g - MEAN_RGB[1]) / STD_RGB[1]
        c2[i] = (r - MEAN_RGB[2]) / STD_RGB[2]
      }
    }
    buffer.put(c0)
    buffer.put(c1)
    buffer.put(c2)
    buffer.rewind()
    return buffer
  }

  private fun copyAssetToFile(context: Context, assetPath: String): File {
    val target = File(context.filesDir, assetPath)
    if (target.exists() && target.length() > 0) return target
    target.parentFile?.mkdirs()
    context.assets.open(assetPath).use { input ->
      target.outputStream().use { output -> input.copyTo(output) }
    }
    return target
  }

  override fun close() {
    detectorSession.close()
    rtmposeSession.close()
  }

  private companion object {
    // Minimum mean RTMPose keypoint confidence for the deg-0 (image-space)
    // rating to be trusted over the rotated search's best rating.
    const val MIN_RTMPOSE_KP = 0.3f
    const val DETECTOR_SIZE = 320
    const val RTMPOSE_SIZE = 256

    // Raw RTMPose outputs 512 SimCC bins per axis; split ratio 2.0 per the
    // export's pipeline.json (256 * 2 = 512), so bin / 2.0 = 256-space px.
    const val SIMCC_SIZE = 512
    const val SIMCC_SPLIT_RATIO = 2f

    // Detected boxes are expanded 1.2x around the center and made square so
    // the crops include the whole hand (mirrors the verified Python
    // pipeline). The search additionally tries a 0.85x (tighter) crop so
    // tall/thin detection boxes don't drown the hand in background.
    const val BOX_EXPANSION = 1.2f
    val FALLBACK_FACTORS = floatArrayOf(1.2f, 0.85f)

    // Edge thumb-only fallback: a single half-width strip per edge over
    // the lower 60% of the image (tuned so 5_kimbo's left-edge hand is
    // found at det 0.46, thumbC 0.66, while no_score strips fire
    // nothing). Tradeoff: an edge hand in the top 40% would be missed
    // (re-add bands if a sample needs it).
    const val EDGE_STRIP_FRACTION = 0.5f
    val EDGE_BANDS = listOf(0.4f to 1f)

    // Skip the fallback when the full-frame detector's best box is below
    // this hint (5_kimbo leaves a 0.167 full-frame response). Kept at
    // 0.10, not higher: the zoomed strip detection is largely independent
    // of the full-frame response, so a hand even more out of frame could
    // score < 0.12 full-frame yet still read a confident thumb in an edge
    // strip. 0.10 still skips genuine no-hand photos (their detector
    // noise is far lower), making them cost 0 strip detections.
    const val EDGE_HINT_DET = 0.10f
    val CANDIDATE_ROTATIONS = listOf(0f, 90f, 180f, 270f)

    // Baked NMS params from the original RTMDet export (read from its
    // initializers): caller-side NMS must match them exactly — verified
    // bit-exact against the baked export on the 31-sample set.
    const val NMS_IOU_THR = 0.6f
    const val NMS_SCORE_THR = 0.05f
    const val NMS_MAX_OUT = 200

    // Minimum side length (px) for a box to be worth landmarking.
    const val MIN_BOX_SIDE = 8
    const val NUM_LANDMARKS = 21

    // mmdet normalization (RGB order); RTMPose consumes BGR pixels with the
    // same stats (verified Python, 0.000 keypoint diff).
    val MEAN_RGB = floatArrayOf(123.675f, 116.28f, 103.53f)
    val STD_RGB = floatArrayOf(58.395f, 57.12f, 57.375f)
  }
}

private fun FloatBuffer.takeFloatArray(): FloatArray {
  val array = FloatArray(remaining())
  get(array)
  return array
}
