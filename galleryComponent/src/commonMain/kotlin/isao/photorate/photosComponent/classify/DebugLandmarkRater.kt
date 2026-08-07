package isao.photorate.galleryComponent.classify

import isao.photorate.inference.classify.HandGestureClassifier
import isao.photorate.inference.classify.LandmarkedImage.Hand
import isao.photorate.inference.classify.LandmarkedImage.Point
import kotlin.math.hypot

/**
 * Dev-mode rater: wraps the real [LandmarkRater] and GUESSES a rating for
 * hands the real rater rejects, so those detections (and their raw landmarks)
 * stay visible for inspection in developer mode instead of being filtered.
 *
 * A hand only gets a guess when it passes basic sanity checks (all 21 points
 * finite, non-negative image-pixel coords, non-degenerate size) — an image
 * with no plausible hand still produces nothing. Guesses carry
 * [Rating.confidence] = 0, which the scan stores as `uncertain`, so guessed
 * images surface in the "best guesses" section of the gallery rather than the
 * confident grid. Activated via [LandmarkRaterProvider] when dev mode is on.
 */
class DebugLandmarkRater(private val delegate: LandmarkRater) : LandmarkRater {

    override fun rate(hand: Hand): Rating? {
        val real = delegate.rate(hand)
        if (real != null) return real
        if (!isPlausibleHand(hand)) return null
        // Best guess: reuse the thumb-angle THUMBS tiers — the primary rating
        // gesture — for any hand that forms no recognized gesture. NOTE: the
        // angle is computed in the hand's own frame (for rotationDegrees != 0
        // hands that is the rotated crop frame, which storage un-rotates
        // later), so the guess is frame-relative — fine for a confidence-0
        // inspection score, not something to turn into a trusted rating.
        return Rating(
            score = HandGestureClassifier.scoreForThumbAngleDegrees(
                HandGestureClassifier.thumbAngleFromUpDegrees(hand.points),
            ),
            confidence = 0f,
        )
    }

    /** Very basic hand sanity checks; rejects point clouds that are not a hand. */
    private fun isPlausibleHand(hand: Hand): Boolean {
        val p = hand.points
        if (p.size != NUM_LANDMARKS) return false
        // Image-pixel space: points must be finite and non-negative.
        if (p.any { !it.x.isFinite() || !it.y.isFinite() || it.x < 0f || it.y < 0f }) return false
        val handSize = dist(p[WRIST], p[MIDDLE_MCP])
        if (handSize <= 0f) return false
        // A real hand's points span a few hand-lengths; a spread wider than
        // that is a scattered/garbage keypoint cloud, not a hand.
        val extent = maxOf(
            p.maxOf { it.x } - p.minOf { it.x },
            p.maxOf { it.y } - p.minOf { it.y },
        )
        return extent / handSize <= MAX_EXTENT_RATIO
    }

    private fun dist(a: Point, b: Point): Float = hypot(a.x - b.x, a.y - b.y)

    private companion object {
        const val NUM_LANDMARKS = 21
        const val WRIST = 0
        const val MIDDLE_MCP = 9

        /** Max landmark-cluster extent / palm depth for a plausible hand. */
        const val MAX_EXTENT_RATIO = 8f
    }
}
