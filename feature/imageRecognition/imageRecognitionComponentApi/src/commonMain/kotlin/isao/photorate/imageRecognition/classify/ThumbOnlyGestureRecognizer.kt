package isao.photorate.imageRecognition.classify

/**
 * More permissive than [ThumbSignalRecognizer]: fingers may be extended. Only requires a visible
 * thumb pointing away from fingers within a plausible up/down range.
 */
class ThumbOnlyGestureRecognizer : GestureRecognizer<ThumbOnlyGesture> {

  override fun recognize(features: HandFeatures2): RecognizedGesture<ThumbOnlyGesture>? {
    if (features.kpMean < MIN_KP_CONFIDENCE) return null
    if (features.handSize <= 0f || features.extentRatio > MAX_EXTENT_RATIO) return null

    with(features.thumb) {
      if (lengthRatio <= FALLBACK_MIN_THUMB_LENGTH) return null
      if (fingerAngle <= FALLBACK_MIN_THUMB_FINGER_ANGLE) return null
      if (tipAngle <= FALLBACK_MIN_TIP_ANGLE) return null

      return RecognizedGesture(ThumbOnlyGesture(tipAngle), confidence = 0f)
    }
  }

  private companion object {
    const val MIN_KP_CONFIDENCE = 0.30f
    const val MAX_EXTENT_RATIO = 2.9f
    const val FALLBACK_MIN_THUMB_LENGTH = 0.4f
    const val FALLBACK_MIN_THUMB_FINGER_ANGLE = 80f
    const val FALLBACK_MIN_TIP_ANGLE = 15f
  }
}
