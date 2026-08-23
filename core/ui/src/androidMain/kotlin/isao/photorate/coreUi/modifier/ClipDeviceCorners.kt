package isao.photorate.coreUi.modifier

import android.os.Build
import android.view.RoundedCorner
import android.view.WindowInsets
import androidx.annotation.RequiresApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView

/**
 * Clips a composable (usually a screen) to roughly the shape of the device.
 *
 * The API used does not provide enough info for a precise shape match, so any possible squircle
 * variations are simplified to the default [RoundedCornerShape] implementation.
 *
 * Neither Material3 nor Navigation3 provide a way for the predictive pop animation to respect the
 * rounded corners of the device. This [Modifier] can be used as a stopgap until it's implemented
 * properly in one of those libraries.
 *
 * Assumes left and right corners are symmetric, RTL is not handled in any specific way.
 */
@Composable
fun Modifier.clipDeviceCorners(): Modifier {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this

  val deviceCorners =
    LocalView.current.rootWindowInsets.let {
      RoundedCornerShape(
        topStart = it.getRoundedCornerRadiusPx(RoundedCorner.POSITION_TOP_LEFT),
        topEnd = it.getRoundedCornerRadiusPx(RoundedCorner.POSITION_TOP_RIGHT),
        bottomStart = it.getRoundedCornerRadiusPx(RoundedCorner.POSITION_BOTTOM_LEFT),
        bottomEnd = it.getRoundedCornerRadiusPx(RoundedCorner.POSITION_BOTTOM_RIGHT),
      )
    }

  return clip(deviceCorners)
}

@RequiresApi(Build.VERSION_CODES.S)
private fun WindowInsets.getRoundedCornerRadiusPx(position: Int) =
  getRoundedCorner(position)?.radius?.toFloat() ?: 0f
