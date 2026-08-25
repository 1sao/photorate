package isao.photorate.imageRecognition.classify

/**
 * An OK-sign gesture: thumb and index form a closed circle facing the camera, remaining fingers
 * extended.
 */
class OkSign : Gesture {
  override val score: Score = Score.THREE
}
