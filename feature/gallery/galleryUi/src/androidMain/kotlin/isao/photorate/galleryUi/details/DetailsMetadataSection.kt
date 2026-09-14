package isao.photorate.galleryUi.details

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import isao.photorate.coreUi.composable.PhotoRatePreview
import isao.photorate.galleryComponent.domain.SystemImageDetails
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun DetailsMetadataSection(details: SystemImageDetails, modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.largeIncreased,
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
    Column(Modifier.padding(20.dp)) {
      Text(
        text = "Details",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Spacer(Modifier.height(8.dp))
      DetailRow("Name", details.displayName)
      DetailRow("Taken", details.dateTakenEpochSeconds?.let(::formatDate))
      DetailRow("Added", details.dateAddedEpochSeconds?.let(::formatDate))
      DetailRow("Modified", details.dateModifiedEpochSeconds?.let(::formatDate))
      DetailRow("Size", details.sizeBytes?.let(::formatBytes))
      if (details.width != null && details.height != null) {
        DetailRow(
          label = "Resolution",
          value = "${details.width} × ${details.height}",
        )
      }
      DetailRow("Type", details.mimeType)
    }
  }
}

@Composable
private fun DetailRow(label: String, value: String?, modifier: Modifier = Modifier) {
  Row(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.width(96.dp),
    )
    Text(
      text = value ?: "—",
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Medium,
    )
  }
}

private fun formatDate(epochSeconds: Long): String =
  DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochSeconds * 1000))

private fun formatBytes(bytes: Long): String =
  when {
    bytes >= 1_048_576 -> "%.1f MB".format(Locale.US, bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.1f KB".format(Locale.US, bytes / 1_024.0)
    else -> "$bytes B"
  }

@Preview
@Composable
private fun DetailsMetadataSectionPreview() {
  PhotoRatePreview {
    DetailsMetadataSection(
      details =
        SystemImageDetails(
          uri = "content://preview/1",
          displayName = "IMG_0001.jpg",
          dateTakenEpochSeconds = 1_700_000_000,
          dateAddedEpochSeconds = 1_700_000_100,
          dateModifiedEpochSeconds = 1_700_000_200,
          sizeBytes = 2_500_000,
          width = 3024,
          height = 4032,
          mimeType = "image/jpeg",
        ),
    )
  }
}
