package isao.photorate.imageRecognition.gesture

import isao.photorate.imageRecognition.Score

/**
 * An OK-sign gesture: thumb and index form a closed circle facing the camera, remaining fingers
 * extended.
 */
class OkSign : Gesture {
  override val score: Score = Score.THREE
}
