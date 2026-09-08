package isao.photorate

import co.touchlab.kermit.Logger
import isao.photorate.core.AppInfo
import isao.photorate.homeUi.HomeUIModule
import isao.photorate.imageRecognition.classify.GestureRecognizerProvider
import isao.photorate.imageRecognition.classify.LandmarkerFactoryProvider
import isao.photorate.imageRecognition.search.AppClipSearchFactory
import org.koin.core.KoinApplication
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.scope.Scope
import org.koin.plugin.module.dsl.startKoin

@org.koin.core.annotation.KoinApplication(
  modules =
    [
      AppModule::class,
      PlatformModule::class,
    ]
)
class MyApp

/**
 * Scans all common components that live in the shared module: repositories, use cases, helpers.
 *
 * `includes` composes the full app graph so this aggregator's scan can resolve cross-module
 * bindings (Logger, SqlDriver, the feature databases) at compile time. The include chain fans out
 * (AppModule → HomeUIModule → {GalleryUIModule, SearchUIModule, ConfigUIModule} → component
 * modules), and Koin deduplicates modules included through multiple paths, so each module is
 * materialized exactly once at runtime.
 */
@Module(includes = [HomeUIModule::class]) @ComponentScan("isao.photorate") class AppModule

/**
 * Platform-specific providers: the hand-landmark model seam and the CLIP search factory. Each
 * platform source set provides an [actual] implementation. The landmarker seam
 * ([LandmarkerFactoryProvider]) is the single place that picks the active on-device model — swap
 * its [isao.photorate.imageRecognition.classify.LandmarkModel] there to switch LiteRT / MediaPipe
 * without touching the scan pipeline.
 */
@Module
expect class PlatformModule() {
  @Single fun provideLandmarkerFactoryProvider(scope: Scope): LandmarkerFactoryProvider

  @Single fun provideGestureRecognizerProvider(): GestureRecognizerProvider

  @Single fun provideAppClipSearchFactory(scope: Scope): AppClipSearchFactory
}

fun initKoin(appDeclaration: KoinApplication.() -> Unit = {}): KoinApplication {
  val koinApplication = startKoin<MyApp> { appDeclaration() }

  val koin = koinApplication.koin
  val kermit = koin.get<Logger>()
  val appInfo = koin.get<AppInfo>()
  kermit.v { "App Id ${appInfo.appId}" }

  return koinApplication
}

fun KoinComponent.injectLogger(tag: String): Lazy<Logger> = lazy {
  inject<Logger>().value.withTag(tag)
}
