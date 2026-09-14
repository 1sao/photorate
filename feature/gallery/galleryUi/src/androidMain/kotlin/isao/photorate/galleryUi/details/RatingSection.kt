package isao.photorate.galleryUi.details

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import isao.photorate.coreUi.composable.PhotoRatePreview
import isao.photorate.galleryUi.RatingStarSelector
import isao.photorate.imageRecognition.Score

@Composable
internal fun RatingSection(
  state: ImageDetailsUiState,
  onSetScore: (Int) -> Unit,
  onRemoveClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.largeIncreased,
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
    Column(Modifier.padding(20.dp)) {
      Text(
        text = "Rating",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Spacer(Modifier.height(8.dp))
      RatingStarSelector(
        selected = state.scores.maxOfOrNull { it.score } ?: 0,
        onSelect = onSetScore,
        modifier = Modifier.height(42.dp),
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = "Change the rating by selecting an appropriate number of stars",
        style = MaterialTheme.typography.bodySmall.copy(lineBreak = LineBreak.Heading),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(16.dp))
      HorizontalDivider()
      Spacer(Modifier.height(8.dp))
      TextButton(
        onClick = onRemoveClick,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
      ) {
        Icon(
          imageVector = Icons.Filled.Delete,
          contentDescription = null,
        )
        Spacer(Modifier.width(8.dp))
        Text("Remove from PhotoRate")
      }
    }
  }
}

@Preview
@Composable
private fun RatingSectionPreview() {
  PhotoRatePreview {
    RatingSection(
      state =
        ImageDetailsUiState(
          scores = listOf(Score.FOUR),
          imageUri = "content://preview/1",
        ),
      onSetScore = {},
      onRemoveClick = {},
    )
  }
}
