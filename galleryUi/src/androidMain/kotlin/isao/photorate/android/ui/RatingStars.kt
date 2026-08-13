package isao.photorate.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import isao.photorate.inference.classify.Score

/**
 * A single star whose fill shows [fraction] of its width filled (0f = empty, 1f = full). Used for
 * the per-score rating display on grid cards and details. [filledColor] lets uncertain (best-guess)
 * ratings use a distinct tone.
 */
@Composable
fun RatingStar(
  fraction: Float,
  size: Dp,
  modifier: Modifier = Modifier,
  filledColor: Color = StarColors.filled,
) {
  // The `size` parameter shadows DrawScope.size inside drawWithContent, so
  // the clip rect below uses an explicit `this.size` to reach the DrawScope
  // (px) size instead of the Dp parameter.
  Box(modifier.size(size)) {
    Icon(
      imageVector = Icons.Filled.Star,
      contentDescription = null,
      tint = StarColors.empty,
      modifier = Modifier.matchParentSize(),
    )
    Box(
      Modifier.matchParentSize().drawWithContent {
        clipRect(right = this.size.width * fraction.coerceIn(0f, 1f)) {
          this@drawWithContent.drawContent()
        }
      },
    ) {
      Icon(
        imageVector = Icons.Filled.Star,
        contentDescription = null,
        tint = filledColor,
        modifier = Modifier.matchParentSize(),
      )
    }
  }
}

/**
 * A row of rating stars (one per distinct score), lightly scrimmed for contrast on photos. Pass
 * [filledColor] to distinguish uncertain (best-guess) ratings.
 */
@Composable
fun RatingStarsOverlay(
  scores: List<Score>,
  modifier: Modifier = Modifier,
  filledColor: Color = StarColors.filled,
) {
  if (scores.isEmpty()) return
  Row(
    modifier =
      modifier
        .padding(8.dp)
        .clip(RoundedCornerShape(50))
        .background(OVERLAY_SCRIM)
        .padding(horizontal = 7.dp, vertical = 5.dp),
    horizontalArrangement = Arrangement.spacedBy(3.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    scores
      .sortedBy { it.score }
      .forEach { score ->
        RatingStar(fraction = score.score / 5f, size = 15.dp, filledColor = filledColor)
      }
  }
}

internal object StarColors {
  val filled = Color(0xFFFFC107)
  val empty = Color(0xFF8A8A8A)

  /** Best-guess ratings (uncertain tier) use this tone instead of the confident amber. */
  val uncertain = Color(0xFFFF7043)
}

/** 35% black scrim so the star rows stay visible over photos. */
private val OVERLAY_SCRIM = Color(0x59000000)
