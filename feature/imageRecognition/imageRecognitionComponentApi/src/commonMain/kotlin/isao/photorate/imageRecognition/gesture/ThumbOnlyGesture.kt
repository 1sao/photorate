package isao.photorate.imageRecognition.gesture

import isao.photorate.imageRecognition.Score

/**
 * A best-guess thumbs-up scored from the thumb angle alone, for hands where no full gesture was
 * recognized (e.g. partial hands at the image edge, or hands with extended fingers that don't match
 * any standard gesture).
 *
 * @property angle Thumb tip angle from image-up in degrees (0 = vertical, 180 = down).
 */
class ThumbOnlyGesture(val angle: Float) : Gesture { // TODO unite with ThumbSignal?
  override val score: Score = ThumbSignal.scoreForAngle(angle)
}
