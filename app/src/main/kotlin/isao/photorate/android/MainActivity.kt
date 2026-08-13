package isao.photorate.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.coreui.theme.PhotoRateTheme
import isao.photorate.android.ui.PhotoRateNavHost

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      PhotoRateTheme {
        PhotoRateNavHost(
          onOpenAppSettings = {
            // TODO extract to a util, consider not passing it down the current lengthy hierarchy.
            startActivity(
              Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts(
                  "package",
                  packageName,
                  null,
                ),
              )
            )
          },
          appVersionName = BuildConfig.VERSION_NAME,
          appVersionCode = BuildConfig.VERSION_CODE,
        )
      }
    }
  }
}
