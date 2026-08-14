package isao.photorate.coreUi.composable

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri

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

/** Returns a callback that opens this [uri] in the user's gallery app. */
@Composable
fun rememberOpenImageInGallery(uri: String): () -> Unit {
  val context = LocalContext.current
  return remember(context) {
    {
      context.startActivity(
        Intent(Intent.ACTION_VIEW).apply {
          setDataAndType(uri.toUri(), "image/*")
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
      )
    }
  }
}
