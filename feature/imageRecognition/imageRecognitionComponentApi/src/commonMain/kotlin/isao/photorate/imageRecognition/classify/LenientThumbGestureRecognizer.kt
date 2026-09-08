package isao.photorate.imageRecognition.classify

/**
 * Last-resort recognizer for uncertain thumbs-up detections where the hand is partially visible or
 * the thumb angle falls outside the strict recognizers' range.
 *
 * Three patterns:
 * - **A**: Extended fingers, thumb pointing mostly upward (high tipAngle ≥ 100°), long thumb
 *   (lengthRatio ≥ 1.0) — catches partial/edge hands where the strict recognizers' geometry checks
 *   fail.
 * - **B**: All fingers curled, thumb pointing nearly straight down (tipAngle ≥ 170°) — catches
 *   upside-down thumbs-up that fall outside [ThumbSignalRecognizer]'s tipAngle limit.
 * - **C**: Extended fingers, thumb pointing upward (low tipAngle 10°–35°), no straight fingers —
 *   catches borderline thumbs-up where the thumb points up alongside extended fingers.
 *
 * Returns [ThumbSignal] with confidence 0.
 */
class LenientThumbGestureRecognizer : GestureRecognizer<ThumbSignal> {

  override fun recognize(features: HandFeatures): RecognizedGesture<ThumbSignal>? {
    if (features.kpMean < MIN_KP) return null
    if (features.handSize <= 0f) return null

    with(features.thumb) {
      if (lengthRatio <= MIN_LENGTH_RATIO) return null

      val hasStraightFinger = features.fingers.any { it.isStraight }
      val hasExtendedFinger = features.fingers.any { it.isExtended }

      // Pattern A: extended fingers, high tipAngle, long thumb
      if (hasExtendedFinger && tipAngle in HIGH_TIP_RANGE) {
        if (lengthRatio >= LONG_THUMB_RATIO) {
          return RecognizedGesture(ThumbSignal(tipAngle), confidence = 0f)
        }
      }

      // Pattern B: all curled, thumb pointing nearly straight down
      if (features.allCurled && tipAngle >= VERY_HIGH_TIP_MIN) {
        return RecognizedGesture(ThumbSignal(tipAngle), confidence = 0f)
      }

      // Pattern C: extended fingers, low tipAngle, no straight fingers
      if (hasExtendedFinger && !hasStraightFinger && tipAngle in LOW_TIP_RANGE) {
        return RecognizedGesture(ThumbSignal(tipAngle), confidence = 0f)
      }
    }

    return null
  }

  private companion object {
    const val MIN_KP = 0.30f
    const val MIN_LENGTH_RATIO = 0.2f
    val HIGH_TIP_RANGE = 100f..180f
    const val LONG_THUMB_RATIO = 1.0f
    const val VERY_HIGH_TIP_MIN = 155f
    val LOW_TIP_RANGE = 10f..35f
  }
}
