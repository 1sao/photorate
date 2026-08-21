package isao.photorate.tracking.firebase

import android.content.Context
import com.google.firebase.crashlytics.FirebaseCrashlytics
import isao.photorate.tracking.CrashReporter
import org.koin.core.annotation.Factory

// TODO @ComponentScan("isao.photorate") already finds it, should we create a separate module?
@Factory
class FirebaseCrashReporter(private val context: Context) : CrashReporter {
  override fun logNonFatal(throwable: Throwable, key: String, extras: Map<String, String>) {
    FirebaseCrashlytics.getInstance().apply {
      setCustomKey("failure_key", key)
      extras.forEach { (k, v) -> setCustomKey(k, v) }
      recordException(throwable)
    }
  }
}
