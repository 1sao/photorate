package isao.photorate.imageRecognition.onnx

import isao.photorate.imageRecognition.recognizer.GestureRecognizer
import isao.photorate.imageRecognition.recognizer.GestureRecognizerProvider
import isao.photorate.imageRecognition.recognizer.LenientThumbGestureRecognizer
import isao.photorate.imageRecognition.recognizer.OkSignRecognizer
import isao.photorate.imageRecognition.recognizer.ThumbOnlyGestureRecognizer
import isao.photorate.imageRecognition.recognizer.ThumbSignalRecognizer

/** ONNX-specific [GestureRecognizerProvider] with all built-in recognizers. */
class OnnxGestureRecognizerProvider : GestureRecognizerProvider {
  override fun createRecognizers(): List<GestureRecognizer<*>> =
    listOf(
      ThumbSignalRecognizer(),
      OkSignRecognizer(),
      ThumbOnlyGestureRecognizer(),
      LenientThumbGestureRecognizer(),
    )
}
