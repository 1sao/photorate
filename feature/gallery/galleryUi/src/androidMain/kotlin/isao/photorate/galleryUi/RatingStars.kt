package isao.photorate.galleryUi

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import isao.photorate.coreUi.composable.PhotoRatePreview
import isao.photorate.coreUi.modifier.noRippleClickable
import isao.photorate.imageRecognition.classify.Score
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun RatingStar(
  fraction: Float,
  modifier: Modifier = Modifier,
  filledColor: Color = StarColors.filled,
  emptyColor: Color = StarColors.empty,
  outlineColor: Color = StarColors.outline,
  outlineWidth: Dp = StarColors.outlineWidth,
) {
  val fillFraction = fraction.coerceIn(0f, 1f)
  Canvas(modifier = modifier.defaultMinSize(16.dp, 16.dp)) {
    val outline = outlineWidth.toPx()
    val canvasSize = this.size
    val radius = canvasSize.minDimension / 2f - outline / 2f
    val centerX = canvasSize.width / 2f
    val centerY = canvasSize.height / 2f
    val starPath = buildStarPath(centerX, centerY, radius)

    drawPath(starPath, color = emptyColor)
    clipRect(
      left = 0f,
      top = 0f,
      right = canvasSize.width * fillFraction,
      bottom = canvasSize.height,
    ) {
      drawPath(starPath, color = filledColor)
    }
    drawPath(starPath, color = outlineColor, style = Stroke(width = outline))
  }
}

@Composable
fun RatingStarSelector(
  // TODO use Score enum
  selected: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
  filledStar: @Composable BoxScope.() -> Unit = {
    RatingStar(
      fraction = 1f,
      modifier = Modifier.matchParentSize(),
      filledColor = MaterialTheme.colorScheme.primary,
      outlineColor = MaterialTheme.colorScheme.primary,
      outlineWidth = 0.dp,
    )
  },
  emptyStar: @Composable BoxScope.() -> Unit = {
    RatingStar(
      fraction = 0f,
      modifier = Modifier.matchParentSize(),
      emptyColor = Color.Transparent,
      outlineColor = MaterialTheme.colorScheme.outline,
      outlineWidth = 2.dp,
    )
  },
) {
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Score.entries.forEach { score ->
      Box(
        Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true)
          .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
          .weight(1f, fill = false)
          .noRippleClickable { onSelect(score.score) },
      ) {
        if (score.score <= selected) {
          filledStar()
        } else {
          emptyStar()
        }
      }
    }
  }
}

internal val Score.ratingStarFraction
  get() =
    when (this) {
      Score.ONE -> 0f
      Score.TWO -> .35f
      Score.THREE -> .5f
      Score.FOUR -> .65f
      Score.FIVE -> 1f
    }

internal object StarColors {
  val filled = Color.White
  val empty = Color.White.copy(alpha = .1f)
  val outline = Color.White
  val outlineWidth = 1.dp
}

// TODO Object creation in Canvas. We should probably avoid this for performance.
// TODO Try using a vector image from material library instead of the custom one.
private fun buildStarPath(centerX: Float, centerY: Float, radius: Float): Path =
  Path().apply {
    for (index in 0..9) {
      val angle = -PI / 2.0 + index * PI / 5.0
      val pointRadius = if (index % 2 == 0) radius else radius * INNER_RADIUS_RATIO
      val x = (centerX + pointRadius * cos(angle).toFloat())
      val y = (centerY + pointRadius * sin(angle).toFloat())
      if (index == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
  }

private const val INNER_RADIUS_RATIO = 0.5f

@Preview
@Composable
private fun RatingStarSelectorPreview() {
  PhotoRatePreview {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
      RatingStarSelector(
        selected = 1,
        onSelect = {},
        modifier = Modifier.height(32.dp),
      )
      RatingStarSelector(
        selected = 3,
        onSelect = {},
      )
      RatingStarSelector(
        selected = 5,
        onSelect = {},
        modifier = Modifier.width(100.dp),
      )
    }
  }
}
