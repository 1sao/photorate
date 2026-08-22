package isao.photorate.galleryComponent.classify

/**
 * A best-guess thumbs-up scored from the thumb angle alone, for hands where no full gesture was
 * recognized (e.g. partial hands at the image edge, or hands with extended fingers that don't match
 * any standard gesture).
 *
 * @property angle Thumb tip angle from image-up in degrees (0 = vertical, 180 = down).
 */
class ThumbOnlyThumbSignal(val angle: Float) : Gesture

/**
 * More permissive than [ThumbSignalRecognizer]: fingers may be extended. Only requires a visible
 * thumb pointing away from fingers within a plausible up/down range.
 */
class ThumbOnlyThumbRecognizer : GestureRecognizer<ThumbOnlyThumbSignal> {

  override fun recognize(features: HandFeatures2): RecognizedGesture<ThumbOnlyThumbSignal>? {
    if (features.kpMean < MIN_KP_CONFIDENCE) return null
    if (features.handSize <= 0f || features.extentRatio > MAX_EXTENT_RATIO) return null

    with(features.thumb) {
      if (lengthRatio <= FALLBACK_MIN_THUMB_LENGTH) return null
      if (fingerAngle <= FALLBACK_MIN_THUMB_FINGER_ANGLE) return null
      if (tipAngle <= FALLBACK_MIN_TIP_ANGLE) return null
      if (tipAngle >= FALLBACK_MAX_TIP_ANGLE) return null

      val confidence = if (features.kpMean >= CONFIDENT_KP) 1f else features.kpMean
      return RecognizedGesture(ThumbOnlyThumbSignal(tipAngle), confidence)
    }
  }

  /** Mean keypoint confidence of the thumb chain (kp1..4). */
  fun HandFeatures2.thumbConfidence(): Float {
    val pts = hand.points
    return (pts[1].z + // TODO magic number
      pts[HandFeatures2.THUMB_MCP].z +
      pts[HandFeatures2.THUMB_IP].z +
      pts[HandFeatures2.THUMB_TIP].z) / 4f
  }

  /** Mean keypoint confidence of the four fingers (kp5..20). */
  fun HandFeatures2.fingerConfidence(): Float {
    val pts = hand.points
    var sum = 0f
    for (i in HandFeatures2.INDEX_MCP until HandFeatures2.NUM_LANDMARKS) sum += pts[i].z
    return sum / (HandFeatures2.NUM_LANDMARKS - HandFeatures2.INDEX_MCP)
  }

  private companion object {
    const val MIN_KP_CONFIDENCE = 0.30f
    const val MAX_EXTENT_RATIO = 2.9f
    const val FALLBACK_MIN_THUMB_LENGTH = 0.4f
    const val FALLBACK_MIN_THUMB_FINGER_ANGLE = 80f
    const val FALLBACK_MIN_TIP_ANGLE = 15f
    const val FALLBACK_MAX_TIP_ANGLE = 165f
    const val CONFIDENT_KP = 0.45f
  }
}
