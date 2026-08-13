package isao.photorate.android

import android.app.Application
import isao.photorate.initKoinAndroid

class MainApp : Application() {

  override fun onCreate() {
    super.onCreate()
    initKoinAndroid(
      app = this,
      versionName = BuildConfig.VERSION_NAME,
      versionCode = BuildConfig.VERSION_CODE,
    )
  }
}
