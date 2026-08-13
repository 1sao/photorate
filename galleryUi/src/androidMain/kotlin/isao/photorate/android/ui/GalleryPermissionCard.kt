package isao.photorate.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.coreui.composable.PhotoRatePreview
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.isGranted
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

@OptIn(ExperimentalPermissionsApi::class)
@Composable
internal fun GalleryPermissionCard(
  permissionState: PermissionState,
  onOpenAppSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var wasPermissionRequestedButNoDialogShown by remember { mutableStateOf(false) }

  fun requestPermissions() {
    permissionState.launchPermissionRequest()
    wasPermissionRequestedButNoDialogShown = true
  }

  LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { wasPermissionRequestedButNoDialogShown = false }

  // Hack: if no dialog was shown for a while after the request, we guess that the system will never
  // show it again.
  val currentOnOpenAppSettings by rememberUpdatedState(onOpenAppSettings)
  LaunchedEffect(permissionState.status.isGranted, wasPermissionRequestedButNoDialogShown) {
    if (!wasPermissionRequestedButNoDialogShown) return@LaunchedEffect
    if (permissionState.status.isGranted) return@LaunchedEffect

    delay(.2.seconds)

    // As the dialog won't be shown again, open system settings as the next best thing.
    currentOnOpenAppSettings()
  }

  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.extraLarge,
    color = MaterialTheme.colorScheme.primaryContainer,
  ) {
    Column(Modifier.padding(20.dp)) {
      Text(
        text = "Allow photo access",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text =
          "PhotoRate needs access to your gallery to look for scored images. Your photos never leave your device.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
      )
      Spacer(Modifier.height(8.dp))
      Button(onClick = ::requestPermissions, modifier = Modifier.align(Alignment.End)) {
        Text("Grant access")
      }
    }
  }
}

@OptIn(ExperimentalPermissionsApi::class)
@Preview
@Composable
private fun GalleryPermissionCardPreview() {
  PhotoRatePreview {
    Box(Modifier.padding(32.dp)) {
      GalleryPermissionCard(
        permissionState =
          object : PermissionState {
            override val permission: String = "permission"
            override val status: PermissionStatus =
              PermissionStatus.Denied(shouldShowRationale = false)

            override fun launchPermissionRequest() {}
          },
        onOpenAppSettings = {},
      )
    }
  }
}
