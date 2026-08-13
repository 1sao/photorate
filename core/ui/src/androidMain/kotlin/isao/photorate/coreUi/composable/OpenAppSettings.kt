package isao.photorate.coreUi.composable

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Returns a callback that opens this app's details page in system settings. */
@Composable
fun rememberOpenAppSettings(): () -> Unit {
  val context = LocalContext.current
  return remember(context) {
    {
      context.startActivity(
        Intent(
          Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
          Uri.fromParts("package", context.packageName, null),
        ),
      )
    }
  }
}
