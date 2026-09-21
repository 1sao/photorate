package isao.photorate.di

import co.touchlab.kermit.Logger
import isao.photorate.core.AppInfo
import isao.photorate.homeUi.HomeUIModule
import isao.photorate.imageRecognition.RecognitionBackend
import isao.photorate.imageRecognition.recognizer.GestureRecognizerProvider
import isao.photorate.imageRecognition.search.AppClipSearchFactory
import org.koin.core.KoinApplication
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import org.koin.plugin.module.dsl.startKoin

@org.koin.core.annotation.KoinApplication(modules = [AppModule::class]) class MyApp

@Module(
  includes =
    [
      HomeUIModule::class,
      PlatformModule::class,
      DatabaseModule::class,
      RecognitionBackendModule::class,
    ],
)
@ComponentScan("isao.photorate")
class AppModule {
  // Utilities to avoid injecting the whole backend at once
  @Single
  fun provideLandmarkerFactoryProvider(@Provided backend: RecognitionBackend) =
    backend.handLandmarkerFactory

  @Single
  fun provideGestureRecognizerProvider(
    @Provided backend: RecognitionBackend
  ): GestureRecognizerProvider = backend.gestureRecognizerProvider

  @Single
  fun provideAppClipSearchFactory(@Provided backend: RecognitionBackend): AppClipSearchFactory =
    backend.appClipSearchFactory
}

@Module expect class RecognitionBackendModule

@Module
expect class PlatformModule() {
  // Put platform-specific dependencies here
}

fun initKoin(appDeclaration: KoinApplication.() -> Unit = {}): KoinApplication {
  val koinApplication = startKoin<MyApp> { appDeclaration() }

  val koin = koinApplication.koin
  val kermit = koin.get<Logger>()
  val appInfo = koin.get<AppInfo>()
  kermit.v { "App Id ${appInfo.appId}" }

  return koinApplication
}
