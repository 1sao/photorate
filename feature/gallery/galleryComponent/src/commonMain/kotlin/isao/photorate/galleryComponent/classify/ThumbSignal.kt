package isao.photorate.galleryComponent.classify

/**
 * A thumbs-up gesture where all fingers are curled and the thumb points away.
 *
 * @property angle Thumb tip angle from image-up in degrees (0 = vertical, 180 = down).
 */
class ThumbSignal(val angle: Float) : Gesture

/**
 * Recognizes a full thumbs-up: all fingers curled AND straight (not gripping), thumb pointing away
 * from fingers, with a plausible up/down orientation.
 */
class ThumbSignalRecognizer : GestureRecognizer<ThumbSignal> {

  override fun recognize(features: HandFeatures2): RecognizedGesture<ThumbSignal>? {
    if (features.handSize <= 0f || features.extentRatio > MAX_EXTENT_RATIO) return null
    if (!features.allCurled) return null // TODO can be dropped?
    if (features.fingers.any { it.isStraight }) return null

    with(features.thumb) {
      if (lengthRatio <= MIN_THUMB_LENGTH) return null
      if (lengthRatio >= MAX_THUMB_LENGTH) return null
      if (fingerAngle <= MIN_THUMB_FINGER_ANGLE) return null
      if (tipAngle >= MAX_TIP_ANGLE) return null
    }

    val confidence = if (features.kpMean >= CONFIDENT_KP) 1f else features.kpMean
    return RecognizedGesture(ThumbSignal(features.thumb.tipAngle), confidence)
  }

  private companion object {
    const val MAX_EXTENT_RATIO = 2.9f
    const val MIN_THUMB_LENGTH = 0.25f
    const val MAX_THUMB_LENGTH = 2.0f
    const val MIN_THUMB_FINGER_ANGLE = 95f
    const val MAX_TIP_ANGLE = 165f
    const val CONFIDENT_KP = 0.45f
  }
}
