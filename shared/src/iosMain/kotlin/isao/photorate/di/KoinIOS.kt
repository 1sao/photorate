@file:OptIn(BetaInteropApi::class)

package isao.photorate.di

import co.touchlab.kermit.Logger
import isao.photorate.IosDatabaseModule
import isao.photorate.core.AppInfo
import isao.photorate.imageRecognition.RecognitionBackend
import kotlin.reflect.KClass
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ObjCClass
import kotlinx.cinterop.ObjCProtocol
import kotlinx.cinterop.getOriginalKotlinClass
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

// TODO delete loggerWithTag and KotlinDependencies?

// Access from Swift to create a logger
@Suppress("unused") fun Koin.loggerWithTag(tag: String) = get<Logger>().withTag(tag)

@Suppress("unused") // Called from Swift
object KotlinDependencies : KoinComponent {
  fun getHandLandmarkerCreator() = getKoin().get<RecognitionBackend>().handLandmarkerFactory
}

fun getKotlinClass(objCClass: ObjCClass): KClass<*>? = getOriginalKotlinClass(objCClass)

fun getKotlinClass(objCProtocol: ObjCProtocol): KClass<*>? = getOriginalKotlinClass(objCProtocol)

// fun get(clazz: ObjCClass): Any = get(clazz, null, null)
//
// fun get(clazz: ObjCClass, qualifier: Qualifier?): Any = get(clazz, qualifier, null)
//
// fun get(
//  clazz: ObjCClass,
//  qualifier: Qualifier?,
//  parameters: ParametersDefinition?,
// ): Any {
//  val kclazz = getKotlinClass(clazz) ?: error("kclass not found for objCClass $clazz")
//  return getKoin().get(clazz = kclazz, qualifier = qualifier, parameters = parameters)
// }
//
// fun get(clazz: ObjCProtocol): Any = get(clazz, null, null)
//
// fun get(clazz: ObjCProtocol, qualifier: Qualifier?): Any = get(clazz, qualifier, null)
//
// fun get(
//  clazz: ObjCProtocol,
//  qualifier: Qualifier?,
//  parameters: ParametersDefinition?,
// ): Any {
//  val kclazz = getKotlinClass(clazz) ?: error("kclass not found for objCProtocol $clazz")
//  return getKoin().get(clazz = kclazz, qualifier = qualifier, parameters = parameters)
// }
