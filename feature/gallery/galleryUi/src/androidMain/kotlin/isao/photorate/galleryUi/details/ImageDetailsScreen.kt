package isao.photorate.galleryUi.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import isao.photorate.coreUi.composable.LocalSharedTransitionScope
import isao.photorate.coreUi.composable.PhotoRatePreview
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
  ImageDetailsScreenContent(
    state = state,
    onBack = onBack,
    onIntent = viewModel::onIntent,
  )
}

@Composable
fun ImageDetailsScreenContent(
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

    BoxWithConstraints {
      val maxImageHeight = maxHeight
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
        ImageSection(state, modifier = Modifier.heightIn(max = maxImageHeight))

        Spacer(Modifier.height(DETAIL_SECTION_SPACING))

        DetailsBottomSection(
          state = state,
          bottomPadding = innerPadding.calculateBottomPadding(),
          onSetScore = { score -> onIntent(ImageDetailsIntent.SetScore(score)) },
          onRemoveClick = { showRemoveDialog = true },
          modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth(),
        )

        Spacer(Modifier.height(DETAIL_SECTION_SPACING))
      }
    }
  }

  if (showRemoveDialog) {
    RemoveImageDialog(
      onDismiss = { showRemoveDialog = false },
      onConfirm = {
        showRemoveDialog = false
        onIntent(ImageDetailsIntent.DeleteImage)
        onBack()
      },
    )
  }
}

@Preview
@Composable
private fun ImageDetailsScreenPreview() {
  PhotoRatePreview {
    ImageDetailsScreenContent(
      state =
        ImageDetailsUiState(
          scores = listOf(Score.FIVE),
          imageUri = "content://preview/1", // TODO display image preview
        ),
      onBack = {},
      onIntent = {},
    )
  }
}

/** Gap between the image and each section. */
internal val DETAIL_SECTION_SPACING = 24.dp
