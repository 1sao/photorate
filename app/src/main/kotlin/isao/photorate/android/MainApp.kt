package isao.photorate.android

import android.app.Application
import isao.photorate.AndroidAppInfo
import isao.photorate.initKoinAndroid

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
