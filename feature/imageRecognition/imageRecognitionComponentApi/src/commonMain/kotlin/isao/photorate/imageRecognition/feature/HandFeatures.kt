package isao.photorate.imageRecognition.feature

import isao.photorate.imageRecognition.landmark.LandmarkedImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/** Structured hand features extracted from 21 keypoints. */
class HandFeatures(val hand: LandmarkedImage.Hand) {

  private val points = hand.points

  val width = run {
    val xs = points.map { it.x }
    xs.max() - xs.min()
  }
  val height = run {
    val ys = points.map { it.y }
    ys.max() - ys.min()
  }
  val handSize: Float = dist(points[WRIST], points[MIDDLE_MCP])
  val extentRatio: Float = max(width, height) / handSize
  val bboxAreaFraction: Float = width * height
  val kpMean: Float = points.sumOf { it.z.toDouble() }.toFloat() / points.size
  val fingerSpread: Float = run {
    val tips = listOf(points[INDEX_TIP], points[MIDDLE_TIP], points[RING_TIP], points[PINKY_TIP])
    var spreadTotal = 0f
    for (i in 0 until tips.size - 1) {
      spreadTotal += dist(tips[i], tips[i + 1])
    }
    spreadTotal / (tips.size - 1) / handSize
  }

  val thumb = Thumb()
  val index = Finger(INDEX_PIP, INDEX_TIP)
  val middle = Finger(MIDDLE_PIP, MIDDLE_TIP)
  val ring = Finger(RING_PIP, RING_TIP)
  val pinky = Finger(PINKY_PIP, PINKY_TIP)
  val fingers: List<Finger> = listOf(index, middle, ring, pinky)

  val allCurled: Boolean = fingers.none { it.isExtended }

  /** A single finger identified by its PIP and TIP keypoint indices. */
  open inner class Finger(pipIndex: Int, tipIndex: Int) {
    /** Finger is extended when its tip is farther from the wrist than its PIP joint. */
    val isExtended: Boolean =
      dist(points[tipIndex], points[WRIST]) > dist(points[pipIndex], points[WRIST])

    /**
     * Finger is straight when PIP→TIP distance / handSize < 0.25. A straight finger is both
     * extended and not curled around an object.
     */
    val isStraight: Boolean =
      if (handSize > 0f) dist(points[tipIndex], points[pipIndex]) / handSize < STRAIGHT_THRESHOLD
      else false
  }

  /** Thumb-specific features beyond the basic [Finger] extension/straightness checks. */
  inner class Thumb : Finger(THUMB_IP, THUMB_TIP) {
    /** Thumb length ratio: MCP→TIP distance / handSize. */
    val lengthRatio: Float = dist(points[THUMB_MCP], points[THUMB_TIP]) / handSize

    /**
     * Thumb tip angle from image-up (degrees), measured on the MCP→TIP vector. 0 = pointing up, 180
     * = pointing down.
     */
    val tipAngle: Float = run {
      val dx = points[THUMB_TIP].x - points[THUMB_MCP].x
      val dy = points[THUMB_TIP].y - points[THUMB_MCP].y
      abs(atan2(dx.toDouble(), -dy.toDouble()) * RAD_TO_DEG).toFloat()
    }

    /**
     * Angle (degrees) between the thumb direction (MCP→TIP) and the index direction (MCP→TIP).
     * Higher values mean the thumb points away from the fingers.
     */
    val fingerAngle: Float = run {
      val tx = points[THUMB_TIP].x - points[THUMB_MCP].x
      val ty = points[THUMB_TIP].y - points[THUMB_MCP].y
      val ix = points[INDEX_TIP].x - points[INDEX_MCP].x
      val iy = points[INDEX_TIP].y - points[INDEX_MCP].y
      angleBetween(tx, ty, ix, iy)
    }

    /**
     * Distance between thumb TIP and index TIP / handSize. Small values indicate the fingertips are
     * touching.
     */
    val indexTipDistance: Float = dist(points[THUMB_TIP], points[INDEX_TIP]) / handSize
  }

  private fun dist(a: LandmarkedImage.Point, b: LandmarkedImage.Point): Float =
    hypot(a.x - b.x, a.y - b.y)

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
fun HandFeatures.thumbConfidence(): Float {
  val pts = hand.points
  return (pts[HandFeatures.THUMB_CMC].z +
    pts[HandFeatures.THUMB_MCP].z +
    pts[HandFeatures.THUMB_IP].z +
    pts[HandFeatures.THUMB_TIP].z) / 4f
}

/** Mean keypoint confidence of the four fingers (kp5..20). */
fun HandFeatures.fingerConfidence(): Float {
  val pts = hand.points
  var sum = 0f
  for (i in HandFeatures.INDEX_MCP until HandFeatures.NUM_LANDMARKS) sum += pts[i].z
  return sum / (HandFeatures.NUM_LANDMARKS - HandFeatures.INDEX_MCP)
}
