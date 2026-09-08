package isao.photorate

import android.app.Application
import isao.photorate.core.AppInfo
import isao.photorate.imageRecognition.classify.GestureRecognizerProvider
import isao.photorate.imageRecognition.classify.LandmarkModel
import isao.photorate.imageRecognition.classify.LandmarkerFactoryProvider
import isao.photorate.imageRecognition.litert.AndroidLiteRtAppClipSearchFactory
import isao.photorate.imageRecognition.litert.AndroidLiteRtHandLandmarkerFactory
import isao.photorate.imageRecognition.litert.LiteRtGestureRecognizerProvider
import isao.photorate.imageRecognition.search.AppClipSearchFactory
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.scope.Scope
import org.koin.dsl.module
import org.koin.plugin.module.dsl.modules

class AndroidAppInfo(
  override val appId: String,
  override val versionName: String,
  override val versionCode: Int,
) : AppInfo

fun initKoinAndroid(app: Application, appInfo: AppInfo): KoinApplication = initKoin {
  androidContext(app)
  workManagerFactory()
  modules(AndroidSqlDriverModule::class)
  modules(module { single { appInfo } })
}

@Module
actual class PlatformModule {

  /**
   * The active Android hand-landmark model is LiteRT (the CompiledModel pipeline running the
   * RTMDet + RTMPose conversions on GPU, CPU fallback). The ONNX provider
   * (imageRecognitionComponentOnnx) is unplugged for now. Switch [LandmarkModel.LITERT] to
   * [LandmarkModel.MEDIAPIPE] to run the MediaPipe pipeline instead.
   */
  @Single
  actual fun provideLandmarkerFactoryProvider(scope: Scope): LandmarkerFactoryProvider =
    DefaultLandmarkerFactoryProvider(
      defaultModel = LandmarkModel.LITERT,
      factories =
        mapOf(
          LandmarkModel.LITERT to AndroidLiteRtHandLandmarkerFactory(scope.get()),
          //          LandmarkModel.MEDIAPIPE to AndroidMediaPipeHandLandmarkerFactory(scope.get()),
        ),
    )

  @Single
  actual fun provideGestureRecognizerProvider(): GestureRecognizerProvider =
    LiteRtGestureRecognizerProvider()

  @Single
  actual fun provideAppClipSearchFactory(scope: Scope): AppClipSearchFactory =
    AndroidLiteRtAppClipSearchFactory(scope.get())
}
