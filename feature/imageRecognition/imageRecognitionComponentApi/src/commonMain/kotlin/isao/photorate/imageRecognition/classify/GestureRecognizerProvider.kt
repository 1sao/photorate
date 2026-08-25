package isao.photorate.imageRecognition.classify

/** Provides the list of [GestureRecognizer]s used by the hand-landmark pipeline. */
fun interface GestureRecognizerProvider {
  fun createRecognizers(): List<GestureRecognizer<*>>
}
