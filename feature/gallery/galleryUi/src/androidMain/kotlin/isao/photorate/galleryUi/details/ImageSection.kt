package isao.photorate.galleryUi.details

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import isao.photorate.coreUi.composable.LocalSharedTransitionScope
import isao.photorate.coreUi.composable.PhotoRatePreview
import isao.photorate.coreUi.composable.imageSharedContentKey
import isao.photorate.coreUi.composable.rememberImageUnavailablePainter
import isao.photorate.imageRecognition.classify.Score
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

@Composable
internal fun ImageSection(
  state: ImageDetailsUiState,
  modifier: Modifier = Modifier,
) {
  val imageKey = imageSharedContentKey(state.imageUri)

  val imageRequest =
    ImageRequest.Builder(LocalContext.current)
      .data(state.imageUri)
      .placeholderMemoryCacheKey(imageKey)
      .memoryCacheKey(imageKey)
      .build()

  val sharedBounds =
    with(LocalSharedTransitionScope.current) {
      Modifier.sharedBounds(
        LocalSharedTransitionScope.current.rememberSharedContentState(key = imageKey),
        LocalNavAnimatedContentScope.current,
        enter = EnterTransition.None,
        exit = fadeOut(snap()),
        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        zIndexInOverlay = -1f,
      )
    }

  // Fill the screen until the size is known to avoid other sections changing positions later.
  var isSizeKnown by remember { mutableStateOf(false) }
  val sizePlaceholder =
    if (isSizeKnown) {
      Modifier
    } else {
      Modifier.fillMaxSize()
    }

  Box(modifier.then(sizePlaceholder)) {
    AsyncImage(
      model = imageRequest,
      contentDescription = null,
      placeholder = null,
      error = rememberImageUnavailablePainter(),
      contentScale = ContentScale.Fit,
      onSuccess = { isSizeKnown = true },
      onError = { isSizeKnown = true },
      modifier =
        Modifier.wrapContentSize()
          .then(sharedBounds)
          .clip(MaterialTheme.shapes.largeIncreased)
          // TODO Use ZoomableAsyncImage to allow for better zoom
          .zoomable(rememberZoomableState()),
    )
    if (state.devModeEnabled && state.landmarkHands.isNotEmpty()) {
      LandmarkOverlay(
        hands = state.landmarkHands,
        modifier = Modifier.matchParentSize(),
      )
    }
  }
}

@Preview
@Composable
private fun ImageSectionPreview() {
  PhotoRatePreview {
    ImageSection(
      state =
        ImageDetailsUiState(
          scores = listOf(Score.FIVE),
          imageUri = "content://preview/1",
        ),
    )
  }
}
