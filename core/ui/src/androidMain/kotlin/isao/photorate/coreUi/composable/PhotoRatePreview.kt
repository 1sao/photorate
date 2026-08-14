package isao.photorate.coreUi.composable

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import coil3.ColorImage
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import isao.photorate.coreUi.theme.PhotoRateTheme

/**
 * A wrapper for Compose previews. Applies [PhotoRateTheme] and provides the composition locals the
 * feature screens rely on — [LocalSharedTransitionScope] (via [SharedTransitionLayout]) and
 * [LocalNavAnimatedContentScope] (via [AnimatedContent]) — so shared-element screens can be
 * previewed without a navigation host; as well as for other utilities.
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalCoilApi::class)
@Composable
fun PhotoRatePreview(content: @Composable () -> Unit) {
  PhotoRateTheme {
    SharedTransitionLayout {
      val previewHandler = AsyncImagePreviewHandler { ColorImage(Color.Red.toArgb()) }

      CompositionLocalProvider(
        LocalSharedTransitionScope provides this,
        LocalAsyncImagePreviewHandler provides previewHandler,
      ) {
        AnimatedContent(targetState = Unit, label = "preview") { animatedState ->
          animatedState.toString() // Suppress a warning that does not apply to previews.
          CompositionLocalProvider(LocalNavAnimatedContentScope provides this) { content() }
        }
      }
    }
  }
}
