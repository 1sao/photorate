package isao.photorate.di

import android.content.Context
import isao.photorate.imageRecognition.RecognitionBackend
import isao.photorate.imageRecognition.onnx.AndroidOnnxHandLandmarkerFactory
import isao.photorate.imageRecognition.onnx.OnnxGestureRecognizerProvider
import isao.photorate.imageRecognition.onnx.search.AndroidAppClipSearchFactory
import isao.photorate.imageRecognition.recognizer.GestureRecognizerProvider
import isao.photorate.imageRecognition.search.AppClipSearchFactory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

class OnnxRecognitionBackend(context: Context) : RecognitionBackend {
  override val handLandmarkerFactory = AndroidOnnxHandLandmarkerFactory(context)
  override val gestureRecognizerProvider: GestureRecognizerProvider =
    OnnxGestureRecognizerProvider()
  override val appClipSearchFactory: AppClipSearchFactory = AndroidAppClipSearchFactory(context)
}

@Module
actual class RecognitionBackendModule {
  @Single
  fun provideRecognitionBackend(context: Context): RecognitionBackend =
    OnnxRecognitionBackend(context)
}
