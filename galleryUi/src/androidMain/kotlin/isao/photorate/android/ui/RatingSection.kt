package isao.photorate.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import isao.photorate.inference.classify.Score

/**
 * The rating card on the details screen: shows the current score(s), a star selector to set the
 * user's own rating, and the "remove from PhotoRate" action.
 */
@Composable
internal fun RatingSection(
  scores: List<Score>,
  hasUserRating: Boolean,
  uncertain: Boolean,
  onSetScore: (Int) -> Unit,
  onRemoveClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(28.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
    Column(Modifier.padding(20.dp)) {
      Text(
        text = "Rating",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Spacer(Modifier.height(8.dp))
      if (scores.isEmpty()) {
        Text(
          text = "Not rated",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          scores.forEach { score ->
            RatingStar(
              fraction = score.score / 5f,
              size = 22.dp,
            )
          }
        }
        Spacer(Modifier.height(4.dp))
        Text(
          text =
            when {
              hasUserRating -> "Your rating"
              uncertain -> "Best guess — review this rating"
              scores.size > 1 -> "${scores.size} hands rated"
              else -> "Detected rating"
            },
          style = MaterialTheme.typography.bodySmall,
          color =
            if (uncertain) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Spacer(Modifier.height(16.dp))
      Text(
        text = "Change rating",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(8.dp))
      StarRatingSelector(
        selected = scores.maxOfOrNull { it.score } ?: 0,
        onSelect = onSetScore,
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = "Your rating stays on top of the detected scores, which are kept for statistics.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(16.dp))
      HorizontalDivider()
      Spacer(Modifier.height(8.dp))
      TextButton(
        onClick = onRemoveClick,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
      ) {
        Icon(
          imageVector = Icons.Filled.Delete,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text("Remove from PhotoRate")
      }
    }
  }
}

/** Five tappable stars */
@Composable
private fun StarRatingSelector(selected: Int, onSelect: (Int) -> Unit) {
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    (1..5).forEach { score ->
      Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).clickable { onSelect(score) },
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Filled.Star,
          contentDescription = "Rate $score",
          tint = if (score <= selected) StarColors.filled else StarColors.empty,
          modifier = Modifier.size(34.dp),
        )
      }
    }
  }
}
