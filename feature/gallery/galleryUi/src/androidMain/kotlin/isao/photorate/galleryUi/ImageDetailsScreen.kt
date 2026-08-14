package isao.photorate.galleryUi

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import isao.photorate.coreUi.composable.LocalSharedTransitionScope
import isao.photorate.coreUi.composable.PhotoRatePreview
import isao.photorate.coreUi.composable.SharedContentKey
import isao.photorate.coreUi.composable.interpolate
import isao.photorate.coreUi.composable.rememberOpenImageInGallery
import isao.photorate.imageRecognition.classify.Score
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ImageDetailsScreen(
  uri: String,
  viewModel: ImageDetailsViewModel = koinViewModel { parametersOf(uri) },
  onBack: () -> Unit,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  ImageDetailsScreenContent2(
    state = state,
    onBack = onBack,
    onIntent = viewModel::onIntent,
  )
}

@Composable
fun ImageDetailsScreenContent2(
  state: ImageDetailsUiState,
  onBack: () -> Unit,
  onIntent: (ImageDetailsIntent) -> Unit,
) {
  val imageKey = SharedContentKey.Image(state.imageUri).value

  val imageRequest =
    ImageRequest.Builder(LocalContext.current)
      .data(state.imageUri)
      .placeholderMemoryCacheKey(imageKey)
      .memoryCacheKey(imageKey)
      .build()

  val navSharedElement =
    with(LocalSharedTransitionScope.current) {
      Modifier.sharedBounds(
        LocalSharedTransitionScope.current.rememberSharedContentState(key = imageKey),
        LocalNavAnimatedContentScope.current,
        enter = EnterTransition.None,
        exit = fadeOut(snap()),
        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        zIndexInOverlay = -1f,
        clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(24.dp)),
      )
    }

  // All bottom sections share one bounds so they slide down as a single sheet on pop.
  val bottomSheetSharedBounds =
    with(LocalSharedTransitionScope.current) {
      Modifier.sharedBounds(
        rememberSharedContentState(SharedContentKey.BottomSheet.value),
        LocalNavAnimatedContentScope.current,
        exit = slideOutVertically(targetOffsetY = { it }),
      )
    }

  // The background follows the navigation transition's own animation: animateColor is a
  // child animation of the nav transition, so the fade stays in sync with its actual
  // progress (normal and predictive-back transitions included) without hardcoded durations.
  val navTransition = LocalNavAnimatedContentScope.current.transition
  val backgroundColor =
    navTransition
      .animateColor { state ->
        when (state) {
          EnterExitState.Visible -> MaterialTheme.colorScheme.surfaceDim
          EnterExitState.PreEnter,
          EnterExitState.PostExit -> Color.Transparent
        }
      }
      .value

  var showRemoveDialog by remember { mutableStateOf(false) }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = backgroundColor,
    topBar = {
      TopBar(
        state = state,
        onBack = onBack,
        backgroundColor = backgroundColor,
      )
    },
    contentWindowInsets = WindowInsets(),
  ) { innerPadding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          // The top edge is unpadded so the image can scroll under the rounded toolbar;
          // the top padding keeps the image clear of the toolbar until it is scrolled.
          .padding(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding(),
          )
          .verticalScroll(rememberScrollState()),
    ) {
      ImageSection(
        state = state,
        imageRequest = imageRequest,
        sharedElementModifier = navSharedElement,
      )
      Spacer(Modifier.height(SECTION_SPACING))
      Column(
        modifier =
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(SECTION_CORNER_RADIUS))
            .then(bottomSheetSharedBounds),
      ) {
        RatingSection(
          scores = state.scores,
          hasUserRating = state.hasUserRating,
          uncertain = state.uncertain,
          onSetScore = { score -> onIntent(ImageDetailsIntent.SetScore(score)) },
          onRemoveClick = { showRemoveDialog = true },
          modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(SECTION_SPACING))
        state.details?.let { details ->
          MetadataSection(
            details = details,
            modifier = Modifier.padding(horizontal = 16.dp),
          )
        }
        Spacer(Modifier.height(SECTION_SPACING))
      }
    }
  }

  if (showRemoveDialog) {
    AlertDialog(
      onDismissRequest = { showRemoveDialog = false },
      title = { Text("Remove this photo?") },
      text = {
        Text(
          "PhotoRate will forget this photo and its rating. " +
            "The photo itself stays in your device gallery.",
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            showRemoveDialog = false
            onIntent(ImageDetailsIntent.DeleteImage)
            onBack()
          },
        ) {
          Text("Remove")
        }
      },
      dismissButton = { TextButton(onClick = { showRemoveDialog = false }) { Text("Cancel") } },
    )
  }
}

@Composable
internal fun TopBar(
  state: ImageDetailsUiState,
  onBack: () -> Unit,
  backgroundColor: Color,
  modifier: Modifier = Modifier,
) {
  val sharedBounds =
    with(LocalSharedTransitionScope.current) {
      Modifier.sharedBounds(
        rememberSharedContentState(SharedContentKey.Toolbar.value),
        LocalNavAnimatedContentScope.current,
        enter = slideInVertically(tween(TOOLBAR_ANIM_MS)) { -it } + fadeIn(tween(TOOLBAR_ANIM_MS)),
        exit = slideOutVertically(tween(TOOLBAR_ANIM_MS)) { -it } + fadeOut(tween(TOOLBAR_ANIM_MS)),
      )
    }
  Box(
    modifier =
      modifier
        .fillMaxWidth()
        // The rounded bottom edge masks whatever scrolls underneath, so the image keeps
        // rounded corners while it is behind the toolbar.
        .clip(
          RoundedCornerShape(
            bottomStart = IMAGE_CORNER_RADIUS,
            bottomEnd = IMAGE_CORNER_RADIUS,
          ),
        )
        .background(backgroundColor)
        .then(sharedBounds),
  ) {
    TopAppBar(
      title = {
        // Intentionally empty
      },
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
          containerColor = Color.Transparent,
          navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
          actionIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
  }
}

@Composable
internal fun ImageSection(
  state: ImageDetailsUiState,
  imageRequest: ImageRequest,
  modifier: Modifier = Modifier,
  sharedElementModifier: Modifier = Modifier,
) {
  Box(
    modifier =
      modifier
        .padding(16.dp)
        .clip(RoundedCornerShape(24.dp))
        .then(sharedElementModifier)
        .clip(RoundedCornerShape(IMAGE_CORNER_RADIUS)),
  ) {
    AsyncImage(
      model = imageRequest,
      contentDescription = "Image",
      placeholder = null,
      contentScale = ContentScale.Inside.interpolate(),
      modifier = Modifier.fillMaxSize(),
    )
    if (state.devModeEnabled && state.landmarkHands.isNotEmpty()) {
      LandmarkOverlay(
        hands = state.landmarkHands,
        modifier = Modifier.matchParentSize(),
      )
    }
  }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun ImageDetailsScreenPreview() {
  PhotoRatePreview {
    ImageDetailsScreenContent2(
      state =
        ImageDetailsUiState(
          scores = listOf(Score.FIVE),
          imageUri = "content://preview/1",
        ),
      onBack = {},
      onIntent = {},
    )
  }
}

/** Same corner radius as the gallery grid cards, so the shared element looks identical. */
private val IMAGE_CORNER_RADIUS = 24.dp

/** Corner radius of the bottom sections container (matches the sections' surfaces). */
private val SECTION_CORNER_RADIUS = 28.dp

/** Gap between the image and each section. */
private val SECTION_SPACING = 24.dp

/** Duration of the toolbar slide during the navigation transition. */
private const val TOOLBAR_ANIM_MS = 220
