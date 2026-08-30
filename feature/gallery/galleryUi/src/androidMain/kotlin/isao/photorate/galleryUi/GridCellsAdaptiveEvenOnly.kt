package isao.photorate.galleryUi

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Like [GridCells.Adaptive], but rounds the column count down to an even number so that half-width
 * items (e.g. the unsure entries spanning half the grid) always fill complete rows.
 */
@Stable
class GridCellsAdaptiveEvenOnly(private val minSize: Dp) : GridCells {
  init {
    require(minSize > 0.dp) { "Provided min size should be larger than zero." }
  }

  override fun Density.calculateCrossAxisCellSizes(
    availableSize: Int,
    spacing: Int,
  ): List<Int> {
    val computedCount = maxOf((availableSize + spacing) / (minSize.roundToPx() + spacing), 1)
    val count = if (computedCount % 2 == 0) computedCount else (computedCount - 1).coerceAtLeast(2)
    return calculateCellsCrossAxisSizeImpl(availableSize, count, spacing)
  }

  override fun hashCode(): Int = -minSize.hashCode()

  override fun equals(other: Any?): Boolean {
    return other is GridCellsAdaptiveEvenOnly && minSize == other.minSize
  }
}

private fun calculateCellsCrossAxisSizeImpl(
  gridSize: Int,
  slotCount: Int,
  spacing: Int,
): List<Int> {
  val gridSizeWithoutSpacing = gridSize - spacing * (slotCount - 1)
  val slotSize = gridSizeWithoutSpacing / slotCount
  val remainingPixels = gridSizeWithoutSpacing % slotCount
  return List(slotCount) { slotSize + if (it < remainingPixels) 1 else 0 }
}
