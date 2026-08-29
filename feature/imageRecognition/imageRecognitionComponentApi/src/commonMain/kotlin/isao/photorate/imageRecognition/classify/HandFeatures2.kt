package isao.photorate.imageRecognition.classify

import isao.photorate.imageRecognition.classify.LandmarkedImage.Hand
import isao.photorate.imageRecognition.classify.LandmarkedImage.Point
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/** Structured hand features extracted from 21 keypoints. */
class HandFeatures2(val hand: Hand) {

  private val p = hand.points
  val handSize: Float = dist(p[WRIST], p[MIDDLE_MCP])
  val extentRatio: Float
  val kpMean: Float
  val fingerSpread: Float
  val allCurled: Boolean

  val thumb = Thumb()
  val index = Finger(INDEX_PIP, INDEX_TIP)
  val middle = Finger(MIDDLE_PIP, MIDDLE_TIP)
  val ring = Finger(RING_PIP, RING_TIP)
  val pinky = Finger(PINKY_PIP, PINKY_TIP)
  val fingers: List<Finger> = listOf(index, middle, ring, pinky)

  init {
    val xs = p.map { it.x }
    val ys = p.map { it.y }
    extentRatio = max(xs.max() - xs.min(), ys.max() - ys.min()) / handSize
    kpMean = p.sumOf { it.z.toDouble() }.toFloat() / p.size

    val tips = listOf(p[INDEX_TIP], p[MIDDLE_TIP], p[RING_TIP], p[PINKY_TIP])
    var spreadTotal = 0f
    for (i in 0 until tips.size - 1) {
      spreadTotal += dist(tips[i], tips[i + 1])
    }
    fingerSpread = spreadTotal / (tips.size - 1) / handSize

    allCurled = fingers.none { it.isExtended }
  }

  /** A single finger identified by its PIP and TIP keypoint indices. */
  open inner class Finger(pipIndex: Int, tipIndex: Int) {
    /** Finger is extended when its tip is farther from the wrist than its PIP joint. */
    val isExtended: Boolean = dist(p[tipIndex], p[WRIST]) > dist(p[pipIndex], p[WRIST])

    /**
     * Finger is straight when PIP→TIP distance / handSize < 0.25. A straight finger is both
     * extended and not curled around an object.
     */
    val isStraight: Boolean =
      if (handSize > 0f) dist(p[tipIndex], p[pipIndex]) / handSize < STRAIGHT_THRESHOLD else false
  }

  /** Thumb-specific features beyond the basic [Finger] extension/straightness checks. */
  inner class Thumb : Finger(THUMB_IP, THUMB_TIP) {
    /** Thumb length ratio: MCP→TIP distance / handSize. */
    val lengthRatio: Float = dist(p[THUMB_MCP], p[THUMB_TIP]) / handSize

    /**
     * Thumb tip angle from image-up (degrees), measured on the MCP→TIP vector. 0 = pointing up, 180
     * = pointing down.
     */
    val tipAngle: Float = run {
      val dx = p[THUMB_TIP].x - p[THUMB_MCP].x
      val dy = p[THUMB_TIP].y - p[THUMB_MCP].y
      abs(atan2(dx.toDouble(), -dy.toDouble()) * RAD_TO_DEG).toFloat()
    }

    /**
     * Angle (degrees) between the thumb direction (MCP→TIP) and the index direction (MCP→TIP).
     * Higher values mean the thumb points away from the fingers.
     */
    val fingerAngle: Float = run {
      val tx = p[THUMB_TIP].x - p[THUMB_MCP].x
      val ty = p[THUMB_TIP].y - p[THUMB_MCP].y
      val ix = p[INDEX_TIP].x - p[INDEX_MCP].x
      val iy = p[INDEX_TIP].y - p[INDEX_MCP].y
      angleBetween(tx, ty, ix, iy)
    }

    /**
     * Distance between thumb TIP and index TIP / handSize. Small values indicate the fingertips are
     * touching.
     */
    val indexTipDistance: Float = dist(p[THUMB_TIP], p[INDEX_TIP]) / handSize
  }

  private fun dist(a: Point, b: Point): Float = hypot(a.x - b.x, a.y - b.y)

  private fun angleBetween(ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dot = ax * bx + ay * by
    val mag = hypot(ax, ay) * hypot(bx, by)
    if (mag == 0f) return 0f
    return (acos((dot / mag).coerceIn(-1f, 1f)) * RAD_TO_DEG).toFloat()
  }

  companion object {
    private const val STRAIGHT_THRESHOLD = 0.25f
    private const val RAD_TO_DEG = 180.0 / PI

    const val NUM_LANDMARKS = 21
    const val WRIST = 0
    const val THUMB_CMC = 1
    const val THUMB_MCP = 2
    const val THUMB_IP = 3
    const val THUMB_TIP = 4
    const val INDEX_MCP = 5
    const val INDEX_PIP = 6
    const val INDEX_DIP = 7
    const val INDEX_TIP = 8
    const val MIDDLE_MCP = 9
    const val MIDDLE_PIP = 10
    const val MIDDLE_TIP = 12
    const val RING_PIP = 14
    const val RING_TIP = 16
    const val PINKY_PIP = 18
    const val PINKY_TIP = 20
  }
}

/** Mean keypoint confidence of the thumb chain (kp1..4). */
fun HandFeatures2.thumbConfidence(): Float {
  val pts = hand.points
  return (pts[HandFeatures2.THUMB_CMC].z +
    pts[HandFeatures2.THUMB_MCP].z +
    pts[HandFeatures2.THUMB_IP].z +
    pts[HandFeatures2.THUMB_TIP].z) / 4f
}

/** Mean keypoint confidence of the four fingers (kp5..20). */
fun HandFeatures2.fingerConfidence(): Float {
  val pts = hand.points
  var sum = 0f
  for (i in HandFeatures2.INDEX_MCP until HandFeatures2.NUM_LANDMARKS) sum += pts[i].z
  return sum / (HandFeatures2.NUM_LANDMARKS - HandFeatures2.INDEX_MCP)
}
