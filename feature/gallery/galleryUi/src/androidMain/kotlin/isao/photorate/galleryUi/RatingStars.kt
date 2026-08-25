package isao.photorate.galleryUi

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import isao.photorate.coreUi.modifier.noRippleClickable
import isao.photorate.imageRecognition.classify.Score
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun RatingStar(
  fraction: Float,
  size: Dp,
  modifier: Modifier = Modifier,
  filledColor: Color = StarColors.filled,
  emptyColor: Color = StarColors.empty,
  outlineColor: Color = StarColors.outline,
  outlineWidth: Dp = StarColors.outlineWidth,
) {
  val fillFraction = fraction.coerceIn(0f, 1f)
  Canvas(modifier = modifier.size(size)) {
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
  selected: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
  size: Dp = 34.dp,
  filledColor: Color = StarColors.filled,
  emptyColor: Color = StarColors.empty,
  outlineColor: Color = StarColors.outline,
  outlineWidth: Dp = StarColors.outlineWidth,
) {
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    (1..5).forEach { score ->
      Box(
        modifier = Modifier.size(size).noRippleClickable { onSelect(score) },
        contentAlignment = Alignment.Center,
      ) {
        RatingStar(
          fraction = if (score <= selected) 1f else 0f,
          size = size,
          filledColor = filledColor,
          emptyColor = emptyColor,
          outlineColor = outlineColor,
          outlineWidth = outlineWidth,
        )
      }
    }
  }
}

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
  val filled = Color.White
  val empty = Color.White.copy(alpha = 0.4f)
  val outline = Color.White
  val outlineWidth = 1.dp
}

private val OVERLAY_SCRIM = Color(0x59000000)

private fun buildStarPath(centerX: Float, centerY: Float, radius: Float): Path =
  Path().apply {
    for (index in 0..9) {
      val angle = -PI / 2.0 + index * PI / 5.0
      val pointRadius = if (index % 2 == 0) radius else radius * INNER_RADIUS_RATIO
      val x = (centerX + pointRadius * cos(angle)).toFloat()
      val y = (centerY + pointRadius * sin(angle)).toFloat()
      if (index == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
  }

private const val INNER_RADIUS_RATIO = 0.5
