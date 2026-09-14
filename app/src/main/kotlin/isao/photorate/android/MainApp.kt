package isao.photorate.android

import android.app.Application
import isao.photorate.di.AndroidAppInfo
import isao.photorate.di.initKoinAndroid

class MainApp : Application() {

  override fun onCreate() {
    super.onCreate()
    initKoinAndroid(
      app = this,
      appInfo =
        AndroidAppInfo(
          appId = BuildConfig.APPLICATION_ID,
          versionName = BuildConfig.VERSION_NAME,
          versionCode = BuildConfig.VERSION_CODE,
        ),
    )
  }
}
