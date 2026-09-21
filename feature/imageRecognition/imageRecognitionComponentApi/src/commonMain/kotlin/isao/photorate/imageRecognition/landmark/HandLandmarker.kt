package isao.photorate.imageRecognition.landmark

import isao.photorate.imageRecognition.GestureResult
import isao.photorate.imageRecognition.recognizer.GestureRecognizer

data class CommonLandmarkerOptions(
  val maxNumHands: Int = 2,
  /** Detections below this confidence are not considered valid detections at all */
  val minHandDetectionConfidence: Float = 0.25f,
  /** Detections below this confidence are not considered confident enough to retrieve a gesture */
  val minHandKpConfidence: Float = 0.3f,
  // TODO to be removed
  val minHandConfidentKpConfidence: Float = 0.45f,
  // TODO use it when we're doing inference
  val preferredImageDimension: Int = 640,
)

/**
 * Detects the hands in one image. Returned hands are in IMAGE-PIXEL space — an isometry of the crop
 * the gesture classifier was tuned on, so its ratios and angles are undistorted regardless of image
 * aspect. The scan un-rotates (via [LandmarkedImage.Hand.rotationDegrees]) and normalizes to 0..1
 * for storage.
 */
interface HandLandmarker : AutoCloseable {
  fun detect(candidate: LandmarkCandidate): LandmarkedImage

  /**
   * Detects hands and classifies each using [recognizers]. Returns one [GestureResult] per detected
   * hand. Hands where no recognizer matches are omitted.
   *
   * Default implementation falls back to [detect] and returns unclassified hands.
   */
  fun detectWithRecognizers(
    candidate: LandmarkCandidate,
    recognizers: List<GestureRecognizer<*>>,
  ): List<GestureResult>
}

interface HandLandmarkerFactory {
  fun create(options: CommonLandmarkerOptions = CommonLandmarkerOptions()): HandLandmarker
}
