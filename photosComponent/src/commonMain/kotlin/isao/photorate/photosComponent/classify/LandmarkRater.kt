package isao.photorate.photosComponent.classify

import org.koin.core.annotation.Factory

/**
 * A rated hand: the score plus how confident the rater is in it. Ratings from
 * the real classifier are fully confident ([confidence] = 1); best-guess
 * ratings (dev mode) carry [confidence] = 0, which marks the stored hand
 * uncertain (see DebugLandmarkRater).
 */
data class Rating(val score: Score, val confidence: Float = 1f)

fun interface LandmarkRater {
    /** Rates a hand, or returns null when it is not a recognizable rating gesture. */
    fun rate(hand: LandmarkedImage.Hand): Rating?
}

data class LandmarkedRatedImage(val image: LandmarkedImage, val score: Score)

enum class Score(val score: Int) {
    ONE(1),
    TWO(2),
    THREE(3),
    FOUR(4),
    FIVE(5),
}

@Factory
class LandmarkRaterByThumb : LandmarkRater {
    override fun rate(hand: LandmarkedImage.Hand): Rating? = HandGestureClassifier.classify(hand)?.let { Rating(it.score) }
}
