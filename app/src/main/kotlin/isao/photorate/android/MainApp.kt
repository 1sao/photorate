package isao.photorate.android

import android.app.Application
import isao.photorate.initKoin
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory

class MainApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidContext(this@MainApp)
            workManagerFactory()
        }
    }
}
