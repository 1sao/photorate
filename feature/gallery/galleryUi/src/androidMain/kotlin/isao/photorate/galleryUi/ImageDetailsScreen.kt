package isao.photorate.galleryUi

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import isao.photorate.coreUi.composable.LocalSharedTransitionScope
import isao.photorate.coreUi.composable.PhotoRatePreview
import isao.photorate.coreUi.composable.imageSharedContentKey
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
  var showRemoveDialog by remember { mutableStateOf(false) }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = Color.Transparent,
    topBar = {
      TopBar(
        state = state,
        onBack = onBack,
      )
    },
  ) { innerPadding ->
    val renderInTransition =
      with(LocalSharedTransitionScope.current) {
        Modifier.renderInSharedTransitionScopeOverlay(zIndexInOverlay = -10f)
      }
    val animateEnterExit =
      with(LocalNavAnimatedContentScope.current) { Modifier.animateEnterExit() }
    Box(
      Modifier.then(renderInTransition)
        .then(animateEnterExit)
        .background(MaterialTheme.colorScheme.surfaceDim)
        .fillMaxSize(),
    )

    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(top = innerPadding.calculateTopPadding())
          .padding(start = innerPadding.calculateStartPadding(LocalLayoutDirection.current))
          .padding(end = innerPadding.calculateEndPadding(LocalLayoutDirection.current))
          .padding(horizontal = 16.dp)
          .clip(
            MaterialTheme.shapes.largeIncreased.copy(
              bottomStart = CornerSize(0),
              bottomEnd = CornerSize(0),
            ),
          )
          .verticalScroll(rememberScrollState()),
    ) {
      ImageSection(state)

      Spacer(Modifier.height(DETAIL_SECTION_SPACING))

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
          Modifier.fillMaxWidth()
            .then(renderInTransition)
            .then(animateEnterExit)
            .clip(MaterialTheme.shapes.largeIncreased)
            .padding(bottom = innerPadding.calculateBottomPadding()),
      ) {
        RatingSection(
          scores = state.scores,
          hasUserRating = state.hasUserRating,
          uncertain = state.uncertain,
          onSetScore = { score -> onIntent(ImageDetailsIntent.SetScore(score)) },
          onRemoveClick = { showRemoveDialog = true },
        )
        Spacer(Modifier.height(DETAIL_SECTION_SPACING))
        state.details?.let { details ->
          MetadataSection(
            details = details,
          )
        }
        Spacer(Modifier.height(DETAIL_SECTION_SPACING))
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

  Box(modifier = modifier.then(sharedBounds).clip(MaterialTheme.shapes.largeIncreased)) {
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

/** Gap between the image and each section. */
internal val DETAIL_SECTION_SPACING = 24.dp
