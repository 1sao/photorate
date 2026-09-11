package isao.photorate.coreUi.composable

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import isao.photorate.coreUi.icon.Icons

/** Painter for images that can no longer be loaded, e.g. photos deleted from the device. */
@Composable
fun rememberImageUnavailablePainter(): Painter {
  val tint = MaterialTheme.colorScheme.onSurfaceVariant
  return rememberVectorPainter(remember(tint) { Icons.brokenImage(tint) })
}
