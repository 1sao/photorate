package isao.photorate.imageRecognition.gesture

import isao.photorate.imageRecognition.Score

/** A hand gesture that has a [score]. */
interface Gesture {
  val score: Score
}
