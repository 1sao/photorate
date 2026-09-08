package isao.photorate.coreUi.composable

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Eased vertical gradient from [color] at the top edge to transparent at the bottom edge. */
@Composable
fun StatusBarBackground(
  modifier: Modifier = Modifier,
  color: Color = MaterialTheme.colorScheme.background,
  height: Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 40.dp,
  easing: Easing = LinearOutSlowInEasing,
) {
  Box(modifier.height(height).background(easedVerticalAlphaGradient(color, easing)))
}

private fun easedVerticalAlphaGradient(color: Color, easing: Easing): Brush {
  val colorStops =
    (0..GRADIENT_STOPS).map { step ->
      val fraction = step / GRADIENT_STOPS.toFloat()
      fraction to color.copy(alpha = 1f - easing.transform(fraction))
    }
  return Brush.verticalGradient(colorStops = colorStops.toTypedArray())
}

private const val GRADIENT_STOPS = 16

@Preview
@Composable
private fun StatusBarBackgroundPreview() {
  PhotoRatePreview {
    StatusBarBackground(
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}
