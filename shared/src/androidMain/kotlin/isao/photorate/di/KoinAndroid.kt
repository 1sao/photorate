package isao.photorate.di

import android.app.Application
import isao.photorate.AndroidSqlDriverModule
import isao.photorate.core.AppInfo
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.KoinApplication
import org.koin.core.annotation.Module
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

@Module actual class PlatformModule
