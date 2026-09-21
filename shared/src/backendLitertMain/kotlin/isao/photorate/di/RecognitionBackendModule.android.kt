package isao.photorate.di

import android.content.Context
import isao.photorate.imageRecognition.RecognitionBackend
import isao.photorate.imageRecognition.litert.AndroidLiteRtAppClipSearchFactory
import isao.photorate.imageRecognition.litert.AndroidLiteRtHandLandmarkerFactory
import isao.photorate.imageRecognition.litert.LiteRtGestureRecognizerProvider
import isao.photorate.imageRecognition.recognizer.GestureRecognizerProvider
import isao.photorate.imageRecognition.search.AppClipSearchFactory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

class LiteRtRecognitionBackend(context: Context) : RecognitionBackend {
  override val handLandmarkerFactory = AndroidLiteRtHandLandmarkerFactory(context)
  override val gestureRecognizerProvider: GestureRecognizerProvider =
    LiteRtGestureRecognizerProvider()
  override val appClipSearchFactory: AppClipSearchFactory =
    AndroidLiteRtAppClipSearchFactory(context)
}

@Module
actual class RecognitionBackendModule {
  @Single
  fun provideRecognitionBackend(context: Context): RecognitionBackend =
    LiteRtRecognitionBackend(context)
}
