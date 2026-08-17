package isao.photorate.galleryUi.details

import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import isao.photorate.coreUi.composable.LocalSharedTransitionScope

/** The stack of bottom cards (rating + details) */
@Composable
internal fun DetailsBottomSection(
  state: ImageDetailsUiState,
  bottomPadding: Dp,
  onSetScore: (Int) -> Unit,
  onRemoveClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val renderInTransition =
    with(LocalSharedTransitionScope.current) { Modifier.renderInSharedTransitionScopeOverlay() }
  val animateEnterExit =
    with(LocalNavAnimatedContentScope.current) {
      Modifier.animateEnterExit(
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
      )
    }
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .then(renderInTransition)
        .then(animateEnterExit)
        .clip(MaterialTheme.shapes.largeIncreased)
        .padding(bottom = bottomPadding),
  ) {
    RatingSection(
      state = state,
      onSetScore = onSetScore,
      onRemoveClick = onRemoveClick,
    )
    Spacer(Modifier.height(DETAIL_SECTION_SPACING))
    state.details?.let { details ->
      DetailsMetadataSection(
        details = details,
      )
    }
  }
}
