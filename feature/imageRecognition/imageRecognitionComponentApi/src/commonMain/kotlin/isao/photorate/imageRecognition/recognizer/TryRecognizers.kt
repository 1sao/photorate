package isao.photorate.imageRecognition.recognizer

import isao.photorate.imageRecognition.feature.HandFeatures
import isao.photorate.imageRecognition.gesture.Gesture

/**
 * Iterates through [recognizers] and returns the first recognized gesture with its confidence, or
 * null if no recognizer matches the hand features.
 */
// TODO Messy. Tweak generics to remove this.
fun tryRecognizers(
  features: HandFeatures,
  recognizers: List<GestureRecognizer<*>>,
): Pair<Gesture, Float>? {
  for (recognizer in recognizers) {
    val result = recognizer.recognize(features)
    if (result != null) return result.gesture to result.confidence
  }
  return null
}
