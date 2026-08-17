package isao.photorate.galleryUi.details

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import isao.photorate.coreUi.composable.PhotoRatePreview

@Composable
internal fun RemoveImageDialog(
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Remove this photo?") },
    text = {
      Text(
        "PhotoRate will forget this photo and its rating. " +
          "The photo itself stays in your device gallery.",
      )
    },
    confirmButton = { TextButton(onClick = onConfirm) { Text("Remove") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Preview(showBackground = true, widthDp = 411)
@Composable
private fun RemoveImageDialogPreview() {
  PhotoRatePreview {
    RemoveImageDialog(
      onDismiss = {},
      onConfirm = {},
    )
  }
}
