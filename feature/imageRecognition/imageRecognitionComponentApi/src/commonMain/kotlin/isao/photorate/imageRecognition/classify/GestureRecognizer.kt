package isao.photorate.imageRecognition.classify

/** A recognized gesture together with the confidence of the recognition. */
data class RecognizedGesture<T : Gesture>(val gesture: T, val confidence: Float)

/**
 * Classifies a detected hand into a specific [Gesture], or returns null when the hand does not form
 * the gesture this recognizer is looking for.
 */
fun interface GestureRecognizer<out T : Gesture> {
  fun recognize(features: HandFeatures): RecognizedGesture<out T>?
}
