package isao.photorate.imageRecognition.litert

import isao.photorate.imageRecognition.GestureResult
import isao.photorate.imageRecognition.feature.HandFeatures
import isao.photorate.imageRecognition.feature.thumbConfidence
import isao.photorate.imageRecognition.landmark.HandLandmarker
import isao.photorate.imageRecognition.landmark.LandmarkCandidate
import isao.photorate.imageRecognition.landmark.LandmarkedImage
import isao.photorate.imageRecognition.litert.LiteRtHandLandmarker.Companion.MIN_DET
import isao.photorate.imageRecognition.recognizer.GestureRecognizer
import isao.photorate.imageRecognition.recognizer.tryRecognizers
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Two-stage hand-landmark pipeline: RTMDet hand detection → RTMPose keypoint estimation.
 *
 * Uses the f32clean TFLite models with preprocessing close to rtmlib:
 * - **Detector**: f32clean raw anchors, caller-side NMS (iou=0.5, score_thr=0.05), gray-114
 *   top-left padding, BGR 0–255 mmdet normalization.
 * - **Pose**: f32clean, BGR 0–255, affine warp with black border (matching rtplib's default
 *   `cv2.warpAffine` borderValue=0), bbox_xyxy2cs padding=1.25, SimCC mean decode.
 *
 * Gesture classification is handled by [GestureRecognizer]s passed to [detectWithRecognizers].
 *
 * Host regression harness: `feature/imageRecognition/scripts/test_landmarks_regression.py`
 */
class LiteRtHandLandmarker(
  private val detector: LiteRtEngine,
  private val pose: LiteRtEngine,
) : HandLandmarker, AutoCloseable {

  private val detectorTensor =
    ImageTensor(
      DETECTOR_SIZE,
      DETECTOR_SIZE,
      mean = RTMLIB_DET_MEAN,
      std = RTMLIB_DET_STD,
      layout = ImageTensor.Layout.NCHW,
      channelOrder = ImageTensor.ChannelOrder.BGR,
      scaleTo01 = false,
    )

  private val poseTensor =
    ImageTensor(
      RTMPOSE_SIZE,
      RTMPOSE_SIZE,
      mean = RTMLIB_POSE_MEAN,
      std = RTMLIB_POSE_STD,
      layout = ImageTensor.Layout.NCHW,
      channelOrder = ImageTensor.ChannelOrder.BGR,
      scaleTo01 = false,
    )

  override fun detect(candidate: LandmarkCandidate): LandmarkedImage {
    val image = candidate.toEngineImage()
    val boxes = detectBoxes(image)
    val hands = ArrayList<LandmarkedImage.Hand>()
    for ((box, score) in boxes.take(MAX_HANDS)) {
      if (score < MIN_DET) continue
      val kpResult = rtmposeLandmarks(image, box) ?: continue
      val points = kpResult.keypoints
      val kpMean = points.sumOf { it.z.toDouble() }.toFloat() / NUM_LANDMARKS
      if (kpMean < MIN_KP_CONFIDENCE) continue
      val hand = LandmarkedImage.Hand(points, rotationDegrees = 0f)
      val features = HandFeatures(hand)
      if (features.handSize < MIN_HAND_SIZE) continue
      if (features.thumbConfidence() < MIN_THUMB_CONF) continue
      hands.add(hand)
    }
    return LandmarkedImage(hands = hands, detectedInMs = 0)
  }

  /**
   * Detects hands in [candidate] and classifies each using [recognizers], then scores each
   * recognized gesture. Returns one [GestureResult] per detected hand. Hands where no recognizer
   * matches are omitted.
   */
  override fun detectWithRecognizers(
    candidate: LandmarkCandidate,
    recognizers: List<GestureRecognizer<*>>,
  ): List<GestureResult> {
    val image = candidate.toEngineImage()
    val boxes = detectBoxes(image)
    val results = ArrayList<GestureResult>()
    // Select the first and the second option boxes
    for ((index, entry) in boxes.take(MAX_HANDS).withIndex()) {
      val (box, score) = entry
      val isTier2 = index > 0 && score < MIN_DET
      if (isTier2) {
        if (score < TIER2_MIN_DET) continue
      } else if (score < MIN_DET) {
        continue
      }
      val handResult = classifyBox(image, box, recognizers) ?: continue
      if (isTier2 && !isTier2Admissible(handResult, box)) continue
      results.add(handResult)
    }
    return results
  }

  /**
   * Check if a box shows promise as a second option: only a confident read on a geometrically
   * plausible hand is accepted.
   */
  private fun isTier2Admissible(result: GestureResult, box: IntArray): Boolean {
    if (result.confidence < TIER2_KP_FLOOR) return false
    val boxSide = max(box[2] - box[0], box[3] - box[1]).toFloat()
    if (boxSide <= 0f) return false
    // TODO extract as a HandFeatures properties
    val handSize =
      dist(result.hand.points[HandFeatures.WRIST], result.hand.points[HandFeatures.MIDDLE_MCP])
    if (handSize / boxSide < MIN_HAND_TO_BOX_RATIO) return false
    val thumbLength =
      dist(result.hand.points[HandFeatures.THUMB_MCP], result.hand.points[HandFeatures.THUMB_TIP])
    if (thumbLength / handSize > MAX_THUMB_LENGTH_RATIO) return false
    return true
  }

  private fun dist(a: LandmarkedImage.Point, b: LandmarkedImage.Point): Float =
    hypot(a.x - b.x, a.y - b.y)

  private fun classifyBox(
    image: EngineImage,
    box: IntArray,
    recognizers: List<GestureRecognizer<*>>,
  ): GestureResult? {
    val kpResult = rtmposeLandmarks(image, box)
    val points = kpResult.keypoints

    val hand = LandmarkedImage.Hand(points, rotationDegrees = 0f)
    val features = HandFeatures(hand)
    if (features.handSize < MIN_HAND_SIZE) return null
    val recognized = tryRecognizers(features, recognizers)
    // TODO try improving using:
    //  val recognized = recognizers.map { it.recognize(features) }.maxByOrNull { it?.confidence}

    return when {
      recognized != null -> GestureResult(recognized.first, recognized.second, hand)
      features.meanKeypointConfidence >= MIN_KP_CONFIDENCE -> GestureResult(null, 0f, hand)
      else -> null
    }
  }

  private fun detectBoxes(image: EngineImage): List<Pair<IntArray, Float>> {
    val iw = image.width
    val ih = image.height
    val ratio = min(DETECTOR_SIZE / iw.toFloat(), DETECTOR_SIZE / ih.toFloat())
    val nw = max((iw * ratio).toInt(), 1)
    val nh = max((ih * ratio).toInt(), 1)
    val padded = image.resized(nw, nh).drawnOn(DETECTOR_SIZE, DETECTOR_SIZE, 0, 0, RTMLIB_DET_PAD)

    val input = detectorTensor.load(padded)
    detector.writeFloatInput(0, input)
    detector.run()
    val boxesRaw = detector.readOutput(0)
    val scoresRaw = detector.readOutput(1)
    val keep = MathOps.nms(boxesRaw, scoresRaw, RTMLIB_NMS_IOU, RTMLIB_NMS_SCORE)

    val boxes = ArrayList<Pair<IntArray, Float>>(keep.size)
    for (i in keep) {
      val o = i * 4
      val s = scoresRaw[i]
      val x0 = max((boxesRaw[o] / ratio).toInt(), 0)
      val y0 = max((boxesRaw[o + 1] / ratio).toInt(), 0)
      val x1 = min((boxesRaw[o + 2] / ratio).toInt(), iw)
      val y1 = min((boxesRaw[o + 3] / ratio).toInt(), ih)
      if (x1 - x0 < MIN_BOX_SIDE || y1 - y0 < MIN_BOX_SIDE) continue
      boxes.add(intArrayOf(x0, y0, x1, y1) to s)
    }
    boxes.sortByDescending { it.second }
    return boxes
  }

  private class KpResult(val keypoints: List<LandmarkedImage.Point>)

  /**
   * Runs RTMPose using rtmlib's exact crop pipeline: `bbox_xyxy2cs(padding=1.25)` →
   * `top_down_affine` → model → postprocess.
   */
  private fun rtmposeLandmarks(
    image: EngineImage,
    box: IntArray,
  ): KpResult {
    val x1 = box[0].toFloat()
    val y1 = box[1].toFloat()
    val x2 = box[2].toFloat()
    val y2 = box[3].toFloat()

    val cx = (x1 + x2) / 2f
    val cy = (y1 + y2) / 2f
    val bw = (x2 - x1) * RTPLIB_CROP
    val bh = (y2 - y1) * RTPLIB_CROP
    val scale = max(bw, bh)

    val warp = getWarpMatrix(cx, cy, scale, RTMPOSE_SIZE, RTMPOSE_SIZE)
    val warped = image.affineWarp(warp, RTMPOSE_SIZE, RTMPOSE_SIZE, 0)

    val input = poseTensor.load(warped)
    pose.writeFloatInput(0, input)
    pose.run()
    val simccX = pose.readOutput(0)
    val simccY = pose.readOutput(1)
    val kps256 = decodeSimccMean(simccX, simccY)

    val points = ArrayList<LandmarkedImage.Point>(NUM_LANDMARKS)
    for (i in 0 until NUM_LANDMARKS) {
      val kpx = kps256[i * 3] / RTMPOSE_SIZE * scale + cx - scale / 2f
      val kpy = kps256[i * 3 + 1] / RTMPOSE_SIZE * scale + cy - scale / 2f
      points.add(
        LandmarkedImage.Point(x = kpx, y = kpy, z = kps256[i * 3 + 2]),
      )
    }
    return KpResult(points)
  }

  private fun getWarpMatrix(
    cx: Float,
    cy: Float,
    srcW: Float,
    dstW: Int,
    dstH: Int,
  ): FloatArray {
    val srcDir = floatArrayOf(0f, srcW * -0.5f)
    val dstDir = floatArrayOf(0f, dstW * -0.5f)

    val src0x = cx
    val src0y = cy
    val src1x = cx + srcDir[0]
    val src1y = cy + srcDir[1]
    val src2x = src0x + (-srcDir[1])
    val src2y = src0y + srcDir[0]

    val dst0x = dstW / 2f
    val dst0y = dstH / 2f
    val dst1x = dst0x + dstDir[0]
    val dst1y = dst0y + dstDir[1]
    val dst2x = dst0x + (-dstDir[1])
    val dst2y = dst0y + dstDir[0]

    val det = (src0x - src2x) * (src1y - src2y) - (src1x - src2x) * (src0y - src2y)
    if (det == 0f) return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)

    val m00 = ((dst0x - dst2x) * (src1y - src2y) - (dst1x - dst2x) * (src0y - src2y)) / det
    val m01 = ((dst1x - dst2x) * (src0x - src2x) - (dst0x - dst2x) * (src1x - src2x)) / det
    val m02 = dst0x - m00 * src0x - m01 * src0y
    val m10 = ((dst0y - dst2y) * (src1y - src2y) - (dst1y - dst2y) * (src0y - src2y)) / det
    val m11 = ((dst1y - dst2y) * (src0x - src2x) - (dst0y - dst2y) * (src1x - src2x)) / det
    val m12 = dst0y - m10 * src0x - m11 * src0y

    return floatArrayOf(m00, m01, m02, m10, m11, m12)
  }

  private fun decodeSimccMean(
    simccX: FloatArray,
    simccY: FloatArray,
  ): FloatArray {
    require(
      simccX.size == NUM_LANDMARKS * SIMCC_SIZE && simccY.size == simccX.size,
    ) {
      "unexpected SimCC size ${simccX.size}"
    }
    val out = FloatArray(NUM_LANDMARKS * 3)
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
      out[i * 3] = xi / SIMCC_SPLIT_RATIO
      out[i * 3 + 1] = yi / SIMCC_SPLIT_RATIO
      out[i * 3 + 2] = (xv + yv) / 2f
    }
    return out
  }

  override fun close() {
    detector.close()
    pose.close()
    detectorTensor.release()
    poseTensor.release()
  }

  private companion object {
    const val DETECTOR_SIZE = 320
    const val RTMPOSE_SIZE = 256
    const val RTPLIB_CROP = 1.25f
    const val MIN_DET = 0.25f

    /** Tier-2 admission for the second-ranked box when its det score is below [MIN_DET]. */
    const val TIER2_MIN_DET = 0.15f
    const val TIER2_KP_FLOOR = 0.45f
    const val MIN_HAND_TO_BOX_RATIO = 0.15f
    const val MAX_THUMB_LENGTH_RATIO = 2f

    const val MIN_KP_CONFIDENCE = 0.30f
    const val MAX_HANDS = 2
    const val MIN_BOX_SIDE = 8
    const val NUM_LANDMARKS = 21
    const val SIMCC_SIZE = 512
    const val SIMCC_SPLIT_RATIO = 2f
    const val RTMLIB_DET_PAD = 0xFF727272.toInt()
    const val RTMLIB_NMS_IOU = 0.5f
    const val RTMLIB_NMS_SCORE = 0.05f
    val RTMLIB_DET_MEAN = floatArrayOf(123.675f, 116.28f, 103.53f)
    val RTMLIB_DET_STD = floatArrayOf(58.395f, 57.12f, 57.375f)
    val RTMLIB_POSE_MEAN = floatArrayOf(123.675f, 116.28f, 103.53f)
    val RTMLIB_POSE_STD = floatArrayOf(58.395f, 57.12f, 57.375f)

    // TODO avoid hardcoded sizes, image size might change
    const val MIN_HAND_SIZE = 40f
    const val MIN_THUMB_CONF = 0.35f
  }
}
