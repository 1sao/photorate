package isao.photorate.galleryComponent.classify

import isao.photorate.imageRecognition.classify.HandGestureClassifier
import isao.photorate.imageRecognition.classify.LandmarkedImage
import isao.photorate.imageRecognition.classify.Score
import org.koin.core.annotation.Factory

/**
 * A rated hand: the score plus how confident the rater is in it. Ratings from the real classifier
 * are fully confident ([confidence] = 1); best-guess ratings (dev mode) carry [confidence] = 0,
 * which marks the stored hand uncertain (see DebugLandmarkRater).
 */
data class Rating(val score: Score, val confidence: Float = 1f)

fun interface LandmarkRater {
  /** Rates a hand, or returns null when it is not a recognizable rating gesture. */
  fun rate(hand: LandmarkedImage.Hand): Rating?
}

data class LandmarkedRatedImage(val image: LandmarkedImage, val score: Score)

@Factory
class LandmarkRaterByThumb : LandmarkRater {
  override fun rate(hand: LandmarkedImage.Hand): Rating? =
    HandGestureClassifier.classify(hand)?.let { Rating(it.score) }
}
