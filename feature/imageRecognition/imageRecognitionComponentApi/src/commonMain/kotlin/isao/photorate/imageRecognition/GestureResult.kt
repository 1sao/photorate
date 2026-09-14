package isao.photorate.imageRecognition

import isao.photorate.imageRecognition.gesture.Gesture
import isao.photorate.imageRecognition.landmark.LandmarkedImage

/**
 * A recognized gesture together with detection confidence. The [Score] is derived from
 * [gesture].score.
 */
data class GestureResult(
  val gesture: Gesture?,
  val confidence: Float,
  val hand: LandmarkedImage.Hand,
) {
  val score: Score?
    get() = gesture?.score
}
