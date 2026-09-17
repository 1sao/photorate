package isao.photorate.imageRecognition.recognizer

import isao.photorate.imageRecognition.feature.HandFeatures
import isao.photorate.imageRecognition.gesture.ThumbSignal

/**
 * Last-resort recognizer for uncertain detections where the hand is partially visible. Returns
 * [ThumbSignal] with confidence 0.
 */
class LenientThumbGestureRecognizer : GestureRecognizer<ThumbSignal> {
  override fun recognize(features: HandFeatures): RecognizedGesture<ThumbSignal>? {
    if (features.meanKeypointConfidence < MIN_CONFIDENCE) return null
    if (features.handSize <= 0f) return null
    if (areFingersNonsense(features)) return null

    with(features.thumb) {
      if (lengthRatio <= MIN_LENGTH_RATIO) return null

      val hasStraightFinger = features.fingers.any { it.isStraight }
      val hasExtendedFinger = features.fingers.any { it.isExtended }

      // Pattern A: extended fingers, thumb pointing away from them, long thumb
      if (hasExtendedFinger && fingerAngle >= MIN_FINGER_ANGLE && lengthRatio >= LONG_THUMB_RATIO) {
        return RecognizedGesture(ThumbSignal(tipAngle), confidence = 0f)
      }

      // Pattern B: all curled
      if (features.allCurled) {
        return RecognizedGesture(ThumbSignal(tipAngle), confidence = 0f)
      }

      // Pattern C: extended fingers, no straight fingers, thumb pointing away
      if (hasExtendedFinger && !hasStraightFinger && fingerAngle >= MIN_FINGER_ANGLE) {
        return RecognizedGesture(ThumbSignal(tipAngle), confidence = 0f)
      }
    }

    return null
  }

  private fun areFingersNonsense(features: HandFeatures): Boolean {
    val fingerLengths = features.fingers.map { it.length }.filter { it > 0f }
    return fingerLengths.size == features.fingers.size &&
      fingerLengths.max() / fingerLengths.min() > NONSENSE_FINGER_RATIO
  }

  private companion object {
    const val MIN_CONFIDENCE = 0.30f
    const val MIN_LENGTH_RATIO = 0.2f
    const val LONG_THUMB_RATIO = 1.0f
    const val MIN_FINGER_ANGLE = 75f
    const val NONSENSE_FINGER_RATIO = 2.5f
  }
}
