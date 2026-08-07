package isao.photorate.android

import android.app.Application
import isao.photorate.initKoin
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.plugin.module.dsl.modules

class MainApp : Application() {

  override fun onCreate() {
    super.onCreate()
    initKoin {
      androidContext(this@MainApp)
      workManagerFactory()
      // The app-owned SqlDriver
      // (full merged schema) — the
      // feature
      // modules' `@Provided` driver
      // parameters resolve to this
      // binding.
      modules(AppDatabaseModule::class)
    }
  }
}
