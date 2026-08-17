package isao.photorate.galleryUi.details

import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import isao.photorate.coreUi.composable.LocalSharedTransitionScope
import isao.photorate.coreUi.composable.PhotoRatePreview
import isao.photorate.coreUi.composable.rememberOpenImageInGallery

@Composable
internal fun TopBar(
  state: ImageDetailsUiState,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val renderInTransition =
    with(LocalSharedTransitionScope.current) { Modifier.renderInSharedTransitionScopeOverlay() }
  val animateEnterExit =
    with(LocalNavAnimatedContentScope.current) {
      Modifier.animateEnterExit(
        enter = slideInVertically(initialOffsetY = { -it }),
        exit = slideOutVertically(targetOffsetY = { -it }),
      )
    }

  TopAppBar(
    title = {
      // Intentionally empty
    },
    modifier = modifier.fillMaxWidth().then(renderInTransition).then(animateEnterExit),
    navigationIcon = {
      IconButton(onClick = onBack) {
        Icon(
          // TODO using Icons is no longer recommended, replace with fonts
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = "Back",
        )
      }
    },
    actions = {
      val openGallery = rememberOpenImageInGallery(state.imageUri)
      IconButton(onClick = openGallery) {
        Icon(
          imageVector = isao.photorate.coreUi.icon.Icons.openInNew,
          contentDescription = "Open in gallery",
        )
      }
    },
    colors =
      TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surfaceDim,
        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
        actionIconContentColor = MaterialTheme.colorScheme.onSurface,
      ),
  )
}

@Preview(showBackground = true, widthDp = 411)
@Composable
private fun TopBarPreview() {
  PhotoRatePreview {
    TopBar(
      state = ImageDetailsUiState(imageUri = "content://preview/1"),
      onBack = {},
    )
  }
}
