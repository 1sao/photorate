package isao.photorate

import co.touchlab.kermit.Logger
import isao.photorate.imageRecognition.classify.LandmarkModel
import isao.photorate.imageRecognition.classify.LandmarkerFactoryProvider
import isao.photorate.imageRecognition.mediapipe.IosMediaPipeHandLandmarkerFactory
import isao.photorate.imageRecognition.search.AppClipSearch
import isao.photorate.imageRecognition.search.AppClipSearchFactory
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.component.KoinComponent
import org.koin.core.scope.Scope
import org.koin.plugin.module.dsl.modules

/** Values provided from Swift before Koin starts. Read by the iOS [PlatformModule]. */
internal object IosPlatformConfig {
  lateinit var appInfo: AppInfo
}

fun initKoinIos(appInfo: AppInfo, doOnStartup: () -> Unit): KoinApplication {
  IosPlatformConfig.appInfo = appInfo
  return initKoin {
    // The iOS SqlDriver (full merged schema) — the
    // feature modules'
    // `@Provided` driver parameters resolve to this
    // binding.
    modules(IosDatabaseModule::class)
  }
}

@Module
actual class PlatformModule {

  @Single actual fun provideAppInfo(): AppInfo = IosPlatformConfig.appInfo

  /**
   * iOS ships only the MediaPipe pipeline (the ONNX runtime is not wired for iOS yet), so it is
   * both the default and the only registered model.
   */
  @Single
  actual fun provideLandmarkerFactoryProvider(scope: Scope): LandmarkerFactoryProvider =
    DefaultLandmarkerFactoryProvider(
      defaultModel = LandmarkModel.MEDIAPIPE,
      factories = mapOf(LandmarkModel.MEDIAPIPE to IosMediaPipeHandLandmarkerFactory()),
    )

  @Single
  actual fun provideAppClipSearchFactory(scope: Scope): AppClipSearchFactory =
    // MobileCLIP search is Android-only for now
    // (onnxruntime not yet added
    // for iOS); the factory throws if anything tries to
    // create a session.
    object : AppClipSearchFactory {
      override fun createFromOptions(options: AppClipSearchFactory.Options): AppClipSearch =
        error("MobileCLIP search not implemented on iOS yet")
    }
}

// Access from Swift to create a logger
@Suppress("unused") fun Koin.loggerWithTag(tag: String) = get<Logger>().withTag(tag)

@Suppress("unused") // Called from Swift
object KotlinDependencies : KoinComponent {
  fun getHandLandmarkerCreator() =
    getKoin().get<LandmarkerFactoryProvider>().factoryFor(LandmarkModel.MEDIAPIPE)
}
