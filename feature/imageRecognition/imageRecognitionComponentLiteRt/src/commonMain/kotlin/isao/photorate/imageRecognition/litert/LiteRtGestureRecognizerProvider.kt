package isao.photorate.imageRecognition.litert

import isao.photorate.imageRecognition.classify.GestureRecognizer
import isao.photorate.imageRecognition.classify.GestureRecognizerProvider
import isao.photorate.imageRecognition.classify.LenientThumbGestureRecognizer
import isao.photorate.imageRecognition.classify.OkSignRecognizer
import isao.photorate.imageRecognition.classify.ThumbOnlyGestureRecognizer
import isao.photorate.imageRecognition.classify.ThumbSignalRecognizer

/** LiteRT-specific [GestureRecognizerProvider] with all built-in recognizers. */
class LiteRtGestureRecognizerProvider : GestureRecognizerProvider {
  override fun createRecognizers(): List<GestureRecognizer<*>> =
    listOf(
      ThumbSignalRecognizer(),
      OkSignRecognizer(),
      ThumbOnlyGestureRecognizer(),
      LenientThumbGestureRecognizer(),
    )
}
