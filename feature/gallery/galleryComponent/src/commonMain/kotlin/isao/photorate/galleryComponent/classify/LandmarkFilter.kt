package isao.photorate.galleryComponent.classify

import isao.photorate.imageRecognition.classify.Score
import kotlin.math.abs

fun interface LandmarkFilter {
  fun filter(image: LandmarkedRatedImage): Boolean
}

class LandmarkFilterByHandPresence : LandmarkFilter {
  override fun filter(image: LandmarkedRatedImage): Boolean = image.image.hands.isNotEmpty()
}

class LandmarkFilterByHandSize(private val minHandSize: Float = 0.0001f) : LandmarkFilter {
  override fun filter(image: LandmarkedRatedImage): Boolean =
    image.image.hands.any { hand ->
      hand.points.any { point ->
        abs(point.x) >= minHandSize || abs(point.y) >= minHandSize || abs(point.z) >= minHandSize
      }
    }
}

class LandmarkFilterByScore(private val allowedScores: List<Score>) : LandmarkFilter {
  override fun filter(image: LandmarkedRatedImage): Boolean = image.score in allowedScores
}
