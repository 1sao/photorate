package isao.photorate

import android.app.Application
import isao.photorate.config.AppVersionInfo
import isao.photorate.imageRecognition.classify.LandmarkModel
import isao.photorate.imageRecognition.classify.LandmarkerFactoryProvider
import isao.photorate.imageRecognition.litert.AndroidLiteRtAppClipSearchFactory
import isao.photorate.imageRecognition.litert.AndroidLiteRtHandLandmarkerFactory
import isao.photorate.imageRecognition.mediapipe.AndroidMediaPipeHandLandmarkerFactory
import isao.photorate.imageRecognition.search.AppClipSearchFactory
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.scope.Scope
import org.koin.plugin.module.dsl.modules

/** Values provided from MainApp before Koin starts. Read by [AndroidDatabaseModule]. */
internal object AndroidPlatformConfig {
  lateinit var appVersionInfo: AppVersionInfo
}

object AndroidAppInfo : AppInfo {
  override val appId: String = "isao.photorate"
}

fun initKoinAndroid(
  app: Application,
  versionName: String,
  versionCode: Int,
): KoinApplication {
  AndroidPlatformConfig.appVersionInfo = AppVersionInfo(versionName, versionCode)
  return initKoin {
    androidContext(app)
    workManagerFactory()
    modules(AndroidDatabaseModule::class)
  }
}

@Module
actual class PlatformModule {

  @Single actual fun provideAppInfo(): AppInfo = AndroidAppInfo

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
          LandmarkModel.MEDIAPIPE to AndroidMediaPipeHandLandmarkerFactory(scope.get()),
        ),
    )

  @Single
  actual fun provideAppClipSearchFactory(scope: Scope): AppClipSearchFactory =
    AndroidLiteRtAppClipSearchFactory(scope.get())
}
