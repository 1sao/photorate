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
import org.koin.core.scope.Scope
import org.koin.plugin.module.dsl.startKoin

@org.koin.core.annotation.KoinApplication(modules = [AppModule::class]) class MyApp

@Module(includes = [HomeUIModule::class, PlatformModule::class])
@ComponentScan("isao.photorate")
class AppModule

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
