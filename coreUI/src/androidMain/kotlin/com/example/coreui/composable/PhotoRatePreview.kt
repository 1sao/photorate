package com.example.coreui.composable

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import com.example.coreui.theme.PhotoRateTheme

/**
 * A wrapper for Compose previews. Applies [PhotoRateTheme] and provides the two composition locals
 * the feature screens rely on — [LocalSharedTransitionScope] (via [SharedTransitionLayout]) and
 * [LocalNavAnimatedContentScope] (via [AnimatedContent]) — so shared-element screens can be
 * previewed without a navigation host.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PhotoRatePreview(content: @Composable () -> Unit) {
  PhotoRateTheme {
    SharedTransitionLayout {
      CompositionLocalProvider(LocalSharedTransitionScope provides this) {
        AnimatedContent(targetState = Unit, label = "preview") { animatedState ->
          animatedState.toString() // Suppress a warning that does not apply to previews.
          CompositionLocalProvider(LocalNavAnimatedContentScope provides this) { content() }
        }
      }
    }
  }
}
