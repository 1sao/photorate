package isao.photorate.galleryUi

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import coil3.compose.AsyncImage
import isao.photorate.coreUi.composable.LocalSharedTransitionScope
import isao.photorate.coreUi.composable.PhotoRatePreview
import isao.photorate.coreUi.navigation.IntentHandler
import isao.photorate.imageRecognition.classify.Score
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun ImageDetailsScreen(
  uri: String,
  viewModel: ImageDetailsViewModel = koinViewModel { parametersOf(uri) },
  onNavigationIntent: (GalleryNavigationIntent) -> Unit =
    koinInject<IntentHandler<GalleryNavigationIntent>>()::handle,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  ImageDetailsScreenContent(
    uri = uri,
    state = state,
    onBack = { onNavigationIntent(GalleryNavigationIntent.Back) },
    onIntent = viewModel::onIntent,
  )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable // TODO cleanup
fun ImageDetailsScreenContent(
  state: ImageDetailsUiState,
  uri: String,
  onBack: () -> Unit,
  onIntent: (ImageDetailsIntent) -> Unit,
) {
  with(LocalSharedTransitionScope.current) {
    var fullScreen by remember { mutableStateOf(false) }
    // Sections start hidden so their enter animation plays when the screen
    // opens (AnimatedVisibility only animates on a visible-state change).
    var sectionsShown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { sectionsShown = true }

    var showRemoveDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // System back exits full-screen mode before popping the route.
    BackHandler(enabled = fullScreen) { fullScreen = false }

    val sharedImageState = rememberSharedContentState(key = "image_$uri")
    // While the shared-element transition runs, the photo renders in the
    // transition overlay — above the floating top bar — so the buttons are
    // hidden until the morph lands, then fade back in.
    val transitionActive = isTransitionActive

    // Reusable photo-viewer interaction: pinch zoom, clamped pan, double-tap.
    //    val zoomState = rememberPhotoZoomState()

    val imageAspect =
      state.details?.let { details ->
        val w = details.width ?: return@let null
        val h = details.height ?: return@let null
        if (h > 0) w / h.toFloat() else null
      } ?: 1f

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
      BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        val scrollState = rememberScrollState()
        // Captured so the sizes stay usable inside the nested layout lambdas.
        val viewportHeight = maxHeight
        val viewportWidth = maxWidth
        // A very tall photo would push the rating below the fold, so the
        // image height is capped and the rating below always peeks out.
        val maxImageHeight = viewportHeight * IMAGE_MAX_HEIGHT_FRACTION
        val fittedHeight =
          ((viewportWidth - IMAGE_HORIZONTAL_PADDING * 2) / imageAspect).coerceAtMost(
            maxImageHeight,
          )
        val imageIsTall =
          (viewportWidth - IMAGE_HORIZONTAL_PADDING * 2) / imageAspect > maxImageHeight

        // Full-screen mode morphs the fitted card into the viewport instead
        // of jumping: height, horizontal padding and corner radius all
        // animate together.
        val animatedHeight by
          animateDpAsState(
            targetValue = if (fullScreen) viewportHeight else fittedHeight,
            animationSpec = tween(IMAGE_TOGGLE_ANIM_MS),
            label = "imageHeight",
          )
        val animatedHPadding by
          animateDpAsState(
            targetValue = if (fullScreen) 0.dp else IMAGE_HORIZONTAL_PADDING,
            animationSpec = tween(IMAGE_TOGGLE_ANIM_MS),
            label = "imageHPadding",
          )
        val animatedCorner by
          animateDpAsState(
            targetValue = if (fullScreen) 0.dp else IMAGE_CORNER_RADIUS,
            animationSpec = tween(IMAGE_TOGGLE_ANIM_MS),
            label = "imageCorner",
          )
        // Fit rect of the photo inside its (animated) container, fed to the
        // zoom state so panning can't reveal empty space around the image.
        val contentWidth = (viewportWidth - animatedHPadding * 2).value
        val contentHeight = animatedHeight.value
        val drawnContent =
          if (contentHeight <= 0f || contentWidth / contentHeight > imageAspect) {
            IntSize((contentHeight * imageAspect).toInt(), contentHeight.toInt())
          } else {
            IntSize(contentWidth.toInt(), (contentWidth / imageAspect).toInt())
          }
        // LaunchedEffect(drawnContent) { zoomState.updateContentSize(drawnContent) }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
          Box(
            modifier =
              Modifier.fillMaxWidth()
                .height(animatedHeight)
                .padding(horizontal = animatedHPadding)
                .sharedElement(sharedImageState, LocalNavAnimatedContentScope.current)
                .clip(RoundedCornerShape(animatedCorner))
                .clickable { fullScreen = !fullScreen },
          ) {
            AsyncImage(
              model = uri,
              contentDescription = null,
              // The full photo must be visible (no crop) so the dev-mode
              // landmark overlay lines up with the actual pixels.
              contentScale = ContentScale.Fit,
              modifier = Modifier.matchParentSize(),
            )
            if (state.devModeEnabled && state.landmarkHands.isNotEmpty()) {
              LandmarkOverlay(
                hands = state.landmarkHands,
                modifier = Modifier.matchParentSize(),
              )
            }
          }

          AnimatedVisibility(
            visible = sectionsShown && !fullScreen,
            enter =
              fadeIn(tween(SECTION_ANIM_ENTER_MS)) +
                slideInVertically(tween(SECTION_ANIM_ENTER_MS)) { it },
            exit =
              fadeOut(tween(SECTION_ANIM_EXIT_MS)) +
                slideOutVertically(tween(SECTION_ANIM_EXIT_MS)) { it },
          ) {
            Column {
              // The rating tucks under the photo's bottom edge when the
              // photo is tall, keeping the section above the fold; once
              // the user scrolls down it settles back into place.
              val ratingOverlap by
                animateDpAsState(
                  targetValue =
                    if (imageIsTall && scrollState.value <= 0f) {
                      RATING_OVERLAP
                    } else {
                      0.dp
                    },
                  animationSpec = tween(SECTION_ANIM_ENTER_MS),
                  label = "ratingOverlap",
                )
              Spacer(Modifier.height(16.dp))
              RatingSection(
                scores = state.scores,
                hasUserRating = state.hasUserRating,
                uncertain = state.uncertain,
                onSetScore = { score -> onIntent(ImageDetailsIntent.SetScore(score)) },
                onRemoveClick = { showRemoveDialog = true },
                modifier = Modifier.padding(horizontal = 16.dp).offset(y = -ratingOverlap),
              )
              Spacer(Modifier.height(12.dp))
              state.details?.let { details ->
                MetadataSection(
                  details = details,
                  modifier = Modifier.padding(horizontal = 16.dp),
                )
              }
              Spacer(Modifier.height(24.dp))
            }
          }
        }

        ImageDetailsTopBar(
          visible = !fullScreen && !transitionActive,
          onBack = onBack,
          onOpenInGallery = { openImageInGallery(context, uri) },
          modifier = Modifier.align(Alignment.TopCenter),
        )
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
}

@Preview(showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun ImageDetailsScreenPreview() {
  PhotoRatePreview {
    ImageDetailsScreenContent(
      uri = "content://preview/1",
      state = ImageDetailsUiState(scores = listOf(Score.FIVE)),
      onBack = {},
      onIntent = {},
    )
  }
}

/**
 * Fraction of the viewport the details photo may occupy before the rating below is allowed to
 * overlap it.
 */
private const val IMAGE_MAX_HEIGHT_FRACTION = 0.62f

/** Same corner radius as the gallery grid cards, so the shared element looks identical. */
private val IMAGE_CORNER_RADIUS = 24.dp
private val IMAGE_HORIZONTAL_PADDING = 16.dp

/** How far the rating card tucks under a tall photo, keeping it above the fold. */
private val RATING_OVERLAP = 24.dp
private const val SECTION_ANIM_ENTER_MS = 280
private const val SECTION_ANIM_EXIT_MS = 220

/** Duration of the fitted → full-screen image morph (and back). */
private const val IMAGE_TOGGLE_ANIM_MS = 280
