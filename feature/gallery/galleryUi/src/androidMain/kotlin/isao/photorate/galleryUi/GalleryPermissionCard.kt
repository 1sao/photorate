package isao.photorate.galleryUi

import android.Manifest
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import isao.photorate.coreUi.composable.PhotoRatePreview
import isao.photorate.coreUi.composable.rememberOpenAppSettings
import isao.photorate.coreUi.permission.hasOnlyPartialGalleryAccess
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun GalleryPermissionCard(
  permissionsState: MultiplePermissionsState,
  modifier: Modifier = Modifier,
) {
  val isPartialGalleryAccess = permissionsState.hasOnlyPartialGalleryAccess

  val openAppSettings = rememberOpenAppSettings()
  var wasPermissionRequestedButNoDialogShown by remember { mutableStateOf(false) }

  LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { wasPermissionRequestedButNoDialogShown = false }

  // Hack: if no dialog was shown for a while after the request, we guess that the system will never
  // show it again. Partial reselection always shows the system dialog, so the settings fallback
  // only applies when access is fully denied.
  LaunchedEffect(isPartialGalleryAccess, wasPermissionRequestedButNoDialogShown) {
    if (isPartialGalleryAccess) return@LaunchedEffect
    if (!wasPermissionRequestedButNoDialogShown) return@LaunchedEffect

    delay(.2.seconds)

    // As the dialog won't be shown again, open system settings as the next best thing.
    openAppSettings()
  }

  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.extraLarge,
    color = MaterialTheme.colorScheme.primaryContainer,
  ) {
    Column(Modifier.padding(20.dp)) {
      Text(
        text = if (isPartialGalleryAccess) "Manage photo access" else "Allow photo access",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text =
          if (isPartialGalleryAccess) {
            "PhotoRate can only see the photos you selected. You can change which photos are visible at any time. Your photos never leave your device."
          } else {
            "PhotoRate needs access to your gallery to look for scored images. Your photos never leave your device."
          },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
      )
      Spacer(Modifier.height(8.dp))
      Button(
        onClick = {
          permissionsState.launchMultiplePermissionRequest()
          wasPermissionRequestedButNoDialogShown = true
        },
        modifier = Modifier.align(Alignment.End),
      ) {
        Text(if (isPartialGalleryAccess) "Manage selection" else "Grant access")
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
        permissionsState =
          rememberMultiplePermissionsState(
            permissions = listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
          ),
      )
    }
  }
}
