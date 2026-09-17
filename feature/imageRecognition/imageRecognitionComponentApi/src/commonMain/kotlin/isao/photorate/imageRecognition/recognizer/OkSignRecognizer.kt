package isao.photorate.imageRecognition.recognizer

import isao.photorate.imageRecognition.feature.HandFeatures
import isao.photorate.imageRecognition.gesture.OkSign
import isao.photorate.imageRecognition.landmark.LandmarkedImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/** Recognizes the OK-sign: a closed thumb+index circle with remaining fingers extended. */
class OkSignRecognizer : GestureRecognizer<OkSign> {

  override fun recognize(features: HandFeatures): RecognizedGesture<OkSign>? {
    if (features.handSize <= 0f || features.extentRatio > MAX_EXTENT_RATIO) return null
    if (features.fingers.drop(1).any { !it.isExtended }) return null
    if (features.fingerSpread <= OK_MIN_SPREAD) return null

    with(features.thumb) { if (indexTipDistance >= OK_SIGN_MAX_THUMB_INDEX_DIST) return null }

    if (features.okThumbCurl() < OK_MIN_THUMB_CURL) return null
    if (features.okIndexCurl() < OK_MIN_INDEX_CURL) return null
    if (features.okCircularity() < OK_MIN_CIRCULARITY) return null
    if (features.okRingWidth() < OK_MIN_RING_SIDE || features.okRingHeight() < OK_MIN_RING_SIDE) {
      return null
    }

    val confidence =
      if (features.meanKeypointConfidence >= CONFIDENT_KP) 1f else features.meanKeypointConfidence
    return RecognizedGesture(OkSign(), confidence)
  }

  // --- OK-sign geometry extensions on HandFeatures2 ---

  /** Thumb curl ratio: bent path length / straight length (1 = straight). */
  fun HandFeatures.okThumbCurl(): Float {
    val pts = hand.points
    val straight = dist(pts[HandFeatures.THUMB_MCP], pts[HandFeatures.THUMB_TIP])
    if (straight <= 0f) return 0f
    return (dist(pts[HandFeatures.THUMB_MCP], pts[HandFeatures.THUMB_IP]) +
      dist(pts[HandFeatures.THUMB_IP], pts[HandFeatures.THUMB_TIP])) / straight
  }

  /** Index curl ratio: bent path length / straight length (1 = straight). */
  fun HandFeatures.okIndexCurl(): Float {
    val pts = hand.points
    val straight = dist(pts[HandFeatures.INDEX_MCP], pts[HandFeatures.INDEX_TIP])
    if (straight <= 0f) return 0f
    return (dist(pts[HandFeatures.INDEX_MCP], pts[HandFeatures.INDEX_PIP]) +
      dist(pts[HandFeatures.INDEX_PIP], pts[HandFeatures.INDEX_DIP]) +
      dist(pts[HandFeatures.INDEX_DIP], pts[HandFeatures.INDEX_TIP])) / straight
  }

  /** Circularity of the thumb+index loop: 4pi*area/perimeter^2. 1 = perfect circle. */
  fun HandFeatures.okCircularity(): Float {
    val pts = hand.points
    val ring =
      listOf(
        pts[HandFeatures.THUMB_MCP],
        pts[HandFeatures.THUMB_IP],
        pts[HandFeatures.THUMB_TIP],
        pts[HandFeatures.INDEX_TIP],
        pts[HandFeatures.INDEX_DIP],
        pts[HandFeatures.INDEX_PIP],
      )
    var area = 0f
    for (i in ring.indices) {
      val (x1, y1) = ring[i]
      val (x2, y2) = ring[(i + 1) % ring.size]
      area += x1 * y2 - x2 * y1
    }
    area = abs(area) / 2f
    var perim = 0f
    for (i in ring.indices) {
      perim += dist(ring[i], ring[(i + 1) % ring.size])
    }
    if (perim <= 0f) return 0f
    return 4f * PI.toFloat() * area / (perim * perim)
  }

  /** Ring width: thumb MCP -> index MCP / handSize. */
  fun HandFeatures.okRingWidth(): Float {
    val pts = hand.points
    return dist(pts[HandFeatures.THUMB_MCP], pts[HandFeatures.INDEX_MCP]) / handSize
  }

  /** Ring height: perpendicular distance from tip midpoint to ring base / handSize. */
  fun HandFeatures.okRingHeight(): Float {
    val pts = hand.points
    val tipMidX = (pts[HandFeatures.THUMB_TIP].x + pts[HandFeatures.INDEX_TIP].x) / 2f
    val tipMidY = (pts[HandFeatures.THUMB_TIP].y + pts[HandFeatures.INDEX_TIP].y) / 2f
    val vx = pts[HandFeatures.INDEX_MCP].x - pts[HandFeatures.THUMB_MCP].x
    val vy = pts[HandFeatures.INDEX_MCP].y - pts[HandFeatures.THUMB_MCP].y
    val vlen = hypot(vx.toDouble(), vy.toDouble()).toFloat()
    if (vlen <= 0f) return 0f
    val cross =
      vx * (tipMidY - pts[HandFeatures.THUMB_MCP].y) -
        vy * (tipMidX - pts[HandFeatures.THUMB_MCP].x)
    return abs(cross) / vlen / handSize
  }

  private fun dist(a: LandmarkedImage.Point, b: LandmarkedImage.Point): Float =
    hypot(a.x - b.x, a.y - b.y)

  private companion object {
    const val MAX_EXTENT_RATIO = 2.9f
    const val OK_SIGN_MAX_THUMB_INDEX_DIST = 0.2f
    const val OK_MIN_THUMB_CURL = 1.08f
    const val OK_MIN_INDEX_CURL = 1.15f
    const val OK_MIN_CIRCULARITY = 0.40f
    const val OK_MIN_RING_SIDE = 0.25f
    const val OK_MIN_SPREAD = 0.10f
    const val CONFIDENT_KP = 0.45f
  }
}
