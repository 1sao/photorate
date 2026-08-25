package isao.photorate.imageRecognition.classify

import isao.photorate.imageRecognition.classify.LandmarkedImage.Point
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/** Recognizes the OK-sign: a closed thumb+index circle with remaining fingers extended. */
class OkSignRecognizer : GestureRecognizer<OkSign> {

  override fun recognize(features: HandFeatures2): RecognizedGesture<OkSign>? {
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

    val confidence = if (features.kpMean >= CONFIDENT_KP) 1f else features.kpMean
    return RecognizedGesture(OkSign(), confidence)
  }

  // --- OK-sign geometry extensions on HandFeatures2 ---

  /** Thumb curl ratio: bent path length / straight length (1 = straight). */
  fun HandFeatures2.okThumbCurl(): Float {
    val pts = hand.points
    val straight = dist(pts[HandFeatures2.THUMB_MCP], pts[HandFeatures2.THUMB_TIP])
    if (straight <= 0f) return 0f
    return (dist(pts[HandFeatures2.THUMB_MCP], pts[HandFeatures2.THUMB_IP]) +
      dist(pts[HandFeatures2.THUMB_IP], pts[HandFeatures2.THUMB_TIP])) / straight
  }

  /** Index curl ratio: bent path length / straight length (1 = straight). */
  fun HandFeatures2.okIndexCurl(): Float {
    val pts = hand.points
    val straight = dist(pts[HandFeatures2.INDEX_MCP], pts[HandFeatures2.INDEX_TIP])
    if (straight <= 0f) return 0f
    return (dist(pts[HandFeatures2.INDEX_MCP], pts[HandFeatures2.INDEX_PIP]) +
      dist(pts[HandFeatures2.INDEX_PIP], pts[HandFeatures2.INDEX_DIP]) +
      dist(pts[HandFeatures2.INDEX_DIP], pts[HandFeatures2.INDEX_TIP])) / straight
  }

  /** Circularity of the thumb+index loop: 4pi*area/perimeter^2. 1 = perfect circle. */
  fun HandFeatures2.okCircularity(): Float {
    val pts = hand.points
    val ring =
      listOf(
        pts[HandFeatures2.THUMB_MCP],
        pts[HandFeatures2.THUMB_IP],
        pts[HandFeatures2.THUMB_TIP],
        pts[HandFeatures2.INDEX_TIP],
        pts[HandFeatures2.INDEX_DIP],
        pts[HandFeatures2.INDEX_PIP],
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
  fun HandFeatures2.okRingWidth(): Float {
    val pts = hand.points
    return dist(pts[HandFeatures2.THUMB_MCP], pts[HandFeatures2.INDEX_MCP]) / handSize
  }

  /** Ring height: perpendicular distance from tip midpoint to ring base / handSize. */
  fun HandFeatures2.okRingHeight(): Float {
    val pts = hand.points
    val tipMidX = (pts[HandFeatures2.THUMB_TIP].x + pts[HandFeatures2.INDEX_TIP].x) / 2f
    val tipMidY = (pts[HandFeatures2.THUMB_TIP].y + pts[HandFeatures2.INDEX_TIP].y) / 2f
    val vx = pts[HandFeatures2.INDEX_MCP].x - pts[HandFeatures2.THUMB_MCP].x
    val vy = pts[HandFeatures2.INDEX_MCP].y - pts[HandFeatures2.THUMB_MCP].y
    val vlen = hypot(vx.toDouble(), vy.toDouble()).toFloat()
    if (vlen <= 0f) return 0f
    val cross =
      vx * (tipMidY - pts[HandFeatures2.THUMB_MCP].y) -
        vy * (tipMidX - pts[HandFeatures2.THUMB_MCP].x)
    return abs(cross) / vlen / handSize
  }

  private fun dist(a: Point, b: Point): Float = hypot(a.x - b.x, a.y - b.y)

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
