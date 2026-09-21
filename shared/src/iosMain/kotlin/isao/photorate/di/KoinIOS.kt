package isao.photorate.di

import co.touchlab.kermit.Logger
import isao.photorate.IosDatabaseModule
import isao.photorate.core.AppInfo
import isao.photorate.imageRecognition.RecognitionBackend
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.component.KoinComponent
import org.koin.dsl.module
import org.koin.plugin.module.dsl.modules

fun initKoinIos(appInfo: AppInfo, doOnStartup: () -> Unit): KoinApplication = initKoin {
  modules(IosDatabaseModule::class)
  modules(module { single { appInfo } })
}

@Module actual class PlatformModule

// Access from Swift to create a logger
@Suppress("unused") fun Koin.loggerWithTag(tag: String) = get<Logger>().withTag(tag)

@Suppress("unused") // Called from Swift
object KotlinDependencies : KoinComponent {
  fun getHandLandmarkerCreator() = getKoin().get<RecognitionBackend>().handLandmarkerFactory
}
