package isao.photorate.imageRecognition.classify

/**
 * A thumbs-up gesture where all fingers are curled and the thumb points away.
 *
 * @property angle Thumb tip angle from image-up in degrees (0 = vertical, 180 = down).
 */
class ThumbSignal(val angle: Float) : Gesture {
  override val score: Score = scoreForAngle(angle)

  companion object {
    private const val THUMBS_UP_MAX_ANGLE = 30f
    private const val SCORE_FOUR_MAX_ANGLE = 75f
    private const val SCORE_THREE_MAX_ANGLE = 105f
    private const val SCORE_TWO_MAX_ANGLE = 165f

    fun scoreForAngle(angle: Float): Score =
      when {
        angle < THUMBS_UP_MAX_ANGLE -> Score.FIVE
        angle < SCORE_FOUR_MAX_ANGLE -> Score.FOUR
        angle < SCORE_THREE_MAX_ANGLE -> Score.THREE
        angle < SCORE_TWO_MAX_ANGLE -> Score.TWO
        else -> Score.ONE
      }
  }
}
