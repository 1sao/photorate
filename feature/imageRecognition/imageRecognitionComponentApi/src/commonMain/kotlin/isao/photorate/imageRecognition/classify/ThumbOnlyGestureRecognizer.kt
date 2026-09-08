package isao.photorate.imageRecognition.classify

/**
 * More permissive than [ThumbSignalRecognizer]: fingers may be extended. Only requires a visible
 * thumb pointing within a plausible up/down range of the image's vertical axis.
 *
 * The angular gate measures the thumb direction against the image vertical (tipAngle: 0 = straight
 * up, 90 = horizontal, 180 = straight down) instead of the thumb-vs-index fingerAngle. The fallback
 * only applies to partial/edge hands: fingers not confidently tracked (fingerConfidence low) but
 * the thumb chain solid (thumbConfidence high).
 */
class ThumbOnlyGestureRecognizer : GestureRecognizer<ThumbOnlyGesture> {

  override fun recognize(features: HandFeatures): RecognizedGesture<ThumbOnlyGesture>? {
    if (features.kpMean < MIN_KP_CONFIDENCE) return null
    if (features.handSize <= 0f || features.extentRatio > MAX_EXTENT_RATIO) return null
    if (features.fingerConfidence() >= FALLBACK_MAX_FINGER_CONF) return null
    if (features.thumbConfidence() < FALLBACK_MIN_THUMB_CONF) return null

    with(features.thumb) {
      if (lengthRatio <= FALLBACK_MIN_THUMB_LENGTH) return null
      if (tipAngle <= FALLBACK_MIN_TIP_ANGLE || tipAngle > FALLBACK_MAX_TIP_ANGLE) return null

      return RecognizedGesture(ThumbOnlyGesture(tipAngle), confidence = 0f)
    }
  }

  private companion object {
    const val MIN_KP_CONFIDENCE = 0.30f
    const val MAX_EXTENT_RATIO = 3.3f
    const val FALLBACK_MIN_THUMB_LENGTH = 0.4f
    const val FALLBACK_MIN_TIP_ANGLE = 15f
    const val FALLBACK_MAX_TIP_ANGLE = 165f
    const val FALLBACK_MAX_FINGER_CONF = 0.35f
    const val FALLBACK_MIN_THUMB_CONF = 0.35f
  }
}
