package isao.photorate.coreUi.composable

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

val LocalSharedTransitionScope: ProvidableCompositionLocal<SharedTransitionScope> =
  compositionLocalOf {
    throw IllegalStateException(
      "Unexpected access to LocalSharedTransitionScope. You should only " +
        "access LocalSharedTransitionScope inside a NavEntry passed to NavDisplay.",
    )
  }
