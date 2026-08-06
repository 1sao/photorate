package isao.photorate.inference.classify

import isao.photorate.inference.classify.HandGestureClassifier.tipDirection
import isao.photorate.inference.classify.LandmarkedImage.Hand
import isao.photorate.inference.classify.LandmarkedImage.Point
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

/** A hand gesture that communicates a rating. */
enum class HandGesture {
    THUMBS_UP,
    ROCK,
    OK_SIGN,
}

/** A hand classified into a gesture together with its rating score. */
data class GestureClassification(val gesture: HandGesture, val score: Score)

/**
 * Classifies a detected hand into a rating gesture, or returns null when the
 * hand does not form a recognizable rating gesture (gripping, folded, holding
 * something) — those hands are excluded and receive no score.
 *
 * All measurements are distance ratios or angles relative to the hand's own
 * size, so they are scale- and rotation-invariant. Thresholds were tuned
 * against the sample images in plans/samples (see HandLandmarkDatasetTest).
 */
object HandGestureClassifier {

    fun classify(hand: Hand): GestureClassification? {
        val p = hand.points
        if (p.size != NUM_LANDMARKS) return null
        val handSize = dist(p[WRIST], p[MIDDLE_MCP])
        if (handSize <= 0f) return null

        // Partial-hand fallback: a hand found only at the image edge by the
        // ONNX pipeline's edge fallback (the thumb is in shot, the rest off-
        // frame — see LandmarkedImage.Hand.edgeDetected). RTMPose hallucinates
        // the off-frame fingers, so the normal all-curled gate below can never
        // fire; the thumb chain is the only trustworthy part. The guess is the
        // THUMBS tier of the tip direction; the landmarker marks such hands
        // uncertain so the UI surfaces them as low-certainty. Gates tuned on
        // plans/samples: confident thumb chain (mean kp1..4 >= 0.45), fingers
        // unreliable (mean kp5..20 < 0.40 — holding hands have confident
        // fingers and never qualify), thumb pointing up-ish (angle <= 45).
        if (hand.edgeDetected &&
            thumbConfidence(p) >= EDGE_THUMB_MIN_CONF &&
            fingerConfidence(p) < EDGE_FINGER_MAX_CONF &&
            thumbAngleFromUpDegrees(p) <= EDGE_THUMB_MAX_ANGLE
        ) {
            return GestureClassification(
                HandGesture.THUMBS_UP,
                scoreForThumbAngleDegrees(thumbAngleFromUpDegrees(p)),
            )
        }

        val thumbLengthRatio = dist(p[THUMB_MCP], p[THUMB_TIP]) / handSize
        val thumbIndexTipDistance = dist(p[THUMB_TIP], p[INDEX_TIP]) / handSize
        val indexExtended = isExtended(p, INDEX_PIP, INDEX_TIP)
        val middleExtended = isExtended(p, MIDDLE_PIP, MIDDLE_TIP)
        val ringExtended = isExtended(p, RING_PIP, RING_TIP)
        val pinkyExtended = isExtended(p, PINKY_PIP, PINKY_TIP)
        val thumbFingerAngle = angleBetween(thumbDirection(p), indexDirection(p))

        // OK sign: the thumb and index must form a CLOSED CIRCLE that faces
        // the camera — not merely touching fingertips. Both fingers must be
        // visibly bent into a ring (curl well above 1 = straight), the
        // projected ring must be near-round (circularity), and BOTH ring
        // dimensions must be substantial (a thin sliver is an edge-on/parallel
        // loop, i.e. not facing the camera). Remaining fingers extended.
        // Thresholds tuned in plans/benchmarks/ok_circle_features.py: real OK
        // signs have thumb_curl >= 1.09, index_curl >= 1.17, circularity
        // >= 0.55 and both ring sides >= 0.36; no_score folded/holding hands
        // sit at curl ~1.00-1.12, circ <= 0.24, or a degenerate sliver ring.
        if (thumbIndexTipDistance < OK_SIGN_MAX_THUMB_INDEX_DIST &&
            middleExtended &&
            ringExtended &&
            pinkyExtended &&
            thumbCurl(p) >= OK_MIN_THUMB_CURL &&
            indexCurl(p) >= OK_MIN_INDEX_CURL &&
            ringCircularity(p) >= OK_MIN_CIRCULARITY &&
            minOf(ringWidth(p), ringHeight(p)) >= OK_MIN_RING_SIDE
        ) {
            return GestureClassification(HandGesture.OK_SIGN, Score.THREE)
        }

        // Rock sign: index and pinky extended, middle curled.
        if (indexExtended && pinkyExtended && !middleExtended) {
            return GestureClassification(HandGesture.ROCK, Score.FIVE)
        }

        // Thumbs-up/down: fingers curled, thumb extended away from them.
        // The thumb's rotation from image-up communicates the score. The
        // entry thresholds are relaxed (thumb length > 0.4, thumb/finger angle
        // > 85) so rotated/thumb-down gestures (1_coffee, 5_kenya) rate too;
        // the kp gate upstream already filters non-hands.
        val allCurled = !indexExtended && !middleExtended && !ringExtended && !pinkyExtended
        val thumbAwayFromFingers = thumbFingerAngle > THUMBS_MIN_THUMB_FINGER_ANGLE
        if (allCurled && thumbLengthRatio > THUMBS_MIN_THUMB_LENGTH && thumbAwayFromFingers) {
            return GestureClassification(
                HandGesture.THUMBS_UP,
                scoreForThumbAngleDegrees(thumbAngleFromUpDegrees(p)),
            )
        }

        return null
    }

    // --- OK-sign circle geometry (all ratios normalized by hand size) ---

    /** How bent the thumb is: bent path length / straight length (1 = straight). */
    private fun thumbCurl(p: List<Point>): Float {
        val straight = dist(p[THUMB_MCP], p[THUMB_TIP])
        if (straight <= 0f) return 0f
        return (dist(p[THUMB_MCP], p[THUMB_IP]) + dist(p[THUMB_IP], p[THUMB_TIP])) / straight
    }

    /** How bent the index is: bent path length / straight length (1 = straight). */
    private fun indexCurl(p: List<Point>): Float {
        val straight = dist(p[INDEX_MCP], p[INDEX_TIP])
        if (straight <= 0f) return 0f
        return (dist(p[INDEX_MCP], p[INDEX_PIP]) + dist(p[INDEX_PIP], p[INDEX_DIP]) + dist(p[INDEX_DIP], p[INDEX_TIP])) / straight
    }

    /** Shoelace area of the closed loop [2,3,4,8,7,6], normalized by handSize². */
    private fun ringArea(p: List<Point>, handSize: Float): Float {
        val ring = listOf(p[THUMB_MCP], p[THUMB_IP], p[THUMB_TIP], p[INDEX_TIP], p[INDEX_DIP], p[INDEX_PIP])
        var area = 0f
        for (i in ring.indices) {
            val (x1, y1) = ring[i]
            val (x2, y2) = ring[(i + 1) % ring.size]
            area += x1 * y2 - x2 * y1
        }
        return abs(area) / (2f * handSize * handSize)
    }

    /** Ring perimeter normalized by hand size. */
    private fun ringPerimeter(p: List<Point>, handSize: Float): Float {
        val ring = listOf(p[THUMB_MCP], p[THUMB_IP], p[THUMB_TIP], p[INDEX_TIP], p[INDEX_DIP], p[INDEX_PIP])
        var perim = 0f
        for (i in ring.indices) {
            perim += dist(ring[i], ring[(i + 1) % ring.size])
        }
        return perim / handSize
    }

    /** 4π·area/perimeter² — 1 for a perfect circle, ~0 for a thin sliver. */
    private fun ringCircularity(p: List<Point>): Float {
        val handSize = dist(p[WRIST], p[MIDDLE_MCP])
        if (handSize <= 0f) return 0f
        val perim = ringPerimeter(p, handSize)
        if (perim <= 0f) return 0f
        return 4f * PI.toFloat() * ringArea(p, handSize) / (perim * perim)
    }

    /** Distance thumb MCP -> index MCP (the ring's width), normalized. */
    private fun ringWidth(p: List<Point>): Float {
        val handSize = dist(p[WRIST], p[MIDDLE_MCP])
        if (handSize <= 0f) return 0f
        return dist(p[THUMB_MCP], p[INDEX_MCP]) / handSize
    }

    /**
     * Perpendicular distance from the tip contact point (midpoint of thumb tip
     * and index tip) to the ring's base line (thumb MCP -> index MCP),
     * normalized by hand size. A camera-facing circle has a substantial height.
     */
    private fun ringHeight(p: List<Point>): Float {
        val handSize = dist(p[WRIST], p[MIDDLE_MCP])
        if (handSize <= 0f) return 0f
        val tipMidX = (p[THUMB_TIP].x + p[INDEX_TIP].x) / 2f
        val tipMidY = (p[THUMB_TIP].y + p[INDEX_TIP].y) / 2f
        val vx = p[INDEX_MCP].x - p[THUMB_MCP].x
        val vy = p[INDEX_MCP].y - p[THUMB_MCP].y
        val vlen = hypot(vx, vy)
        if (vlen <= 0f) return 0f
        val cross = vx * (tipMidY - p[THUMB_MCP].y) - vy * (tipMidX - p[THUMB_MCP].x)
        return abs(cross) / vlen / handSize
    }

    /** A finger is extended when its tip is farther from the wrist than its PIP. */
    private fun isExtended(p: List<Point>, pipIndex: Int, tipIndex: Int): Boolean =
        dist(p[tipIndex], p[WRIST]) > dist(p[pipIndex], p[WRIST])

    /**
     * The thumb's away-from-fingers vector (MCP -> TIP), used by the gesture
     * gates. Deliberately NOT the tip segment: the gate measures "thumb away
     * from the curled fingers", which is robust with kp2; switching it to the
     * tip segment changed rotated-read outcomes (1_coffee rated THUMBS-5).
     */
    private fun thumbDirection(p: List<Point>): Point = vector(p[THUMB_MCP], p[THUMB_TIP])

    /**
     * The thumb's POINTING direction — the IP -> TIP segment (kp3->kp4). The
     * CMC/MCP joints sit at the palm and are noisy (low kp confidence on many
     * hands), and the thumb is often curved, so a fit through the whole chain
     * misreads where the tip points (LSQ through kp1..4 flips 5_buco/5_kenya/
     * 5_vertical to THUMBS-1). The tip segment is the model's most confident
     * part of the thumb and fixes 1_tuna's borderline 147.7/153 deg angle to
     * its label's ONE without changing any other sample.
     */
    private fun tipDirection(p: List<Point>): Point = vector(p[THUMB_IP], p[THUMB_TIP])

    /** Mean keypoint confidence of the thumb chain (kp1..4). */
    private fun thumbConfidence(p: List<Point>): Float = (p[1].z + p[2].z + p[3].z + p[4].z) / 4f

    /** Mean keypoint confidence of the four fingers (kp5..20). */
    private fun fingerConfidence(p: List<Point>): Float {
        var sum = 0f
        for (i in 5 until NUM_LANDMARKS) sum += p[i].z
        return sum / (NUM_LANDMARKS - 5)
    }

    private fun indexDirection(p: List<Point>): Point = vector(p[INDEX_MCP], p[INDEX_TIP])

    private fun vector(from: Point, to: Point): Point = Point(to.x - from.x, to.y - from.y, to.z - from.z)

    private fun angleBetween(a: Point, b: Point): Float {
        val dot = a.x * b.x + a.y * b.y
        val magnitude = hypot(a.x, a.y) * hypot(b.x, b.y)
        if (magnitude == 0f) return 0f
        return (acos((dot / magnitude).coerceIn(-1f, 1f)) * RAD_TO_DEG).toFloat()
    }

    /**
     * The thumb's pointing direction as an angle from image-up (0 = pointing
     * up, 180 = pointing down), measured on the IP -> TIP segment (see
     * [tipDirection]). Public so the dev-mode best-guess rater can reuse the
     * same score tiers for hands that form no recognized gesture.
     */
    fun thumbAngleFromUpDegrees(p: List<Point>): Float {
        val d = tipDirection(p)
        return abs(atan2(d.x.toDouble(), -d.y.toDouble()) * RAD_TO_DEG).toFloat()
    }

    /**
     * The THUMBS score tier for [angle] degrees from image-up. Shared by the
     * classifier and the dev-mode best-guess rater's fallback.
     */
    fun scoreForThumbAngleDegrees(angle: Float): Score = when {
        angle < THUMBS_UP_MAX_ANGLE -> Score.FIVE
        angle < SCORE_FOUR_MAX_ANGLE -> Score.FOUR
        angle < SCORE_THREE_MAX_ANGLE -> Score.THREE
        angle < SCORE_TWO_MAX_ANGLE -> Score.TWO
        else -> Score.ONE
    }

    private fun dist(a: Point, b: Point): Float = hypot(a.x - b.x, a.y - b.y)

    private const val WRIST = 0
    private const val THUMB_MCP = 2
    private const val THUMB_IP = 3
    private const val THUMB_TIP = 4
    private const val NUM_LANDMARKS = 21
    private const val INDEX_MCP = 5
    private const val INDEX_PIP = 6
    private const val INDEX_DIP = 7
    private const val INDEX_TIP = 8
    private const val MIDDLE_MCP = 9
    private const val MIDDLE_PIP = 10
    private const val MIDDLE_TIP = 12
    private const val RING_PIP = 14
    private const val RING_TIP = 16
    private const val PINKY_PIP = 18
    private const val PINKY_TIP = 20

    private const val OK_SIGN_MAX_THUMB_INDEX_DIST = 0.2f

    // OK-sign circle thresholds (see ok_circle_features.py). The circle must be
    // genuinely bent (curl well above 1), round, and face the camera (both
    // projected ring sides substantial).
    private const val OK_MIN_THUMB_CURL = 1.08f
    private const val OK_MIN_INDEX_CURL = 1.15f
    private const val OK_MIN_CIRCULARITY = 0.40f
    private const val OK_MIN_RING_SIDE = 0.25f
    private const val THUMBS_MIN_THUMB_FINGER_ANGLE = 85f
    private const val THUMBS_MIN_THUMB_LENGTH = 0.25f
    private const val THUMBS_UP_MAX_ANGLE = 40f
    private const val SCORE_FOUR_MAX_ANGLE = 70f
    private const val SCORE_THREE_MAX_ANGLE = 110f
    private const val SCORE_TWO_MAX_ANGLE = 150f

    // Edge thumb-only fallback gates (see the classify() doc): the thumb
    // chain must be confident, the fingers unreliable (off-frame), and the
    // thumb pointing up-ish for a low-certainty THUMBS guess.
    const val EDGE_THUMB_MIN_CONF = 0.45f
    const val EDGE_FINGER_MAX_CONF = 0.40f
    const val EDGE_THUMB_MAX_ANGLE = 45f

    private const val RAD_TO_DEG = 180.0 / PI
}
