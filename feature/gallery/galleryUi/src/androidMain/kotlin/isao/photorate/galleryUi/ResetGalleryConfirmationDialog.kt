package isao.photorate.galleryUi

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import isao.photorate.coreUi.composable.PhotoRatePreview

@Composable
public fun ResetGalleryConfirmationDialog(
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
  modifier: Modifier = Modifier,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = modifier,
    title = { Text("Rescan gallery?") },
    text = {
      Text(
        "Rescan the whole gallery while keeping scores you assigned manually.",
      )
    },
    confirmButton = { TextButton(onClick = onConfirm) { Text("Yes, rescan") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Preview
@Composable
private fun ResetGalleryConfirmationDialogPreview() {
  PhotoRatePreview {
    ResetGalleryConfirmationDialog(
      onDismiss = {},
      onConfirm = {},
    )
  }
}
