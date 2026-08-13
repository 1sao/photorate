package isao.photorate

import android.app.Application
import isao.photorate.configUi.ConfigNavModule
import isao.photorate.core.AppInfo
import isao.photorate.galleryUi.GalleryNavModule
import isao.photorate.homeUi.HomeNavModule
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
  modules(AndroidDatabaseModule::class)
  modules(module { single { appInfo } })
  // Navigation3 entries, provided per feature module (androidMain): the Koin graph root is
  // where the annotated modules are already aggregated, so entries ride the same assembly.
  modules(HomeNavModule, GalleryNavModule, ConfigNavModule)
  // The navigation intent handlers live at the graph root so compile-safety can validate the
  // screens' koinInject calls against them.
  modules(AppNavigationModule::class)
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
          LandmarkModel.MEDIAPIPE to AndroidMediaPipeHandLandmarkerFactory(scope.get()),
        ),
    )

  @Single
  actual fun provideAppClipSearchFactory(scope: Scope): AppClipSearchFactory =
    AndroidLiteRtAppClipSearchFactory(scope.get())
}
