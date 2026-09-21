package isao.photorate.di

import isao.photorate.imageRecognition.RecognitionBackend
import isao.photorate.imageRecognition.litert.IosLiteRtAppClipSearchFactory
import isao.photorate.imageRecognition.litert.IosLiteRtHandLandmarkerFactory
import isao.photorate.imageRecognition.recognizer.GestureRecognizerProvider
import isao.photorate.imageRecognition.search.AppClipSearchFactory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

/** arm64 actual: kmplitert-backed LiteRT pipeline loading models from the app bundle. */
@OptIn(ExperimentalForeignApi::class)
actual class IosRecognitionBackendFactory actual constructor() {
  actual fun create(): RecognitionBackend {
    val bundle = NSBundle.mainBundle
    val modelsDir = bundle.resourcePath ?: error("Main bundle has no resource path")
    val tokenizerPath =
      bundle.pathForResource("tokenizer", ofType = "json")
        ?: error("tokenizer.json missing from the app bundle")
    val tokenizerJson =
      NSString.stringWithContentsOfFile(
        tokenizerPath,
        encoding = NSUTF8StringEncoding,
        error = null,
      ) ?: error("Failed to read tokenizer.json at $tokenizerPath")
    return object : RecognitionBackend {
      override val handLandmarkerFactory = IosLiteRtHandLandmarkerFactory(modelsDir)
      override val gestureRecognizerProvider: GestureRecognizerProvider =
        GestureRecognizerProvider {
          emptyList()
        }
      override val appClipSearchFactory: AppClipSearchFactory =
        IosLiteRtAppClipSearchFactory(modelsDir, tokenizerJson)
    }
  }
}
