package isao.photorate.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import isao.photorate.galleryUi.ImageDetailsIntent
import isao.photorate.galleryUi.ImageDetailsUiState
import isao.photorate.galleryUi.ImageDetailsViewModel
import isao.photorate.photosComponent.classify.LandmarkedImage.Point
import isao.photorate.photosComponent.classify.Score
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Full-screen details for one rated image. The photo is a shared element with
 * its grid card (key "image_$uri"), so opening/closing animates the image
 * fluidly between the grid and this screen.
 *
 * The rating section shows the detected score(s) and lets the user set their
 * own rating (a fake hand that displays on top of the real detections, which
 * stay in the DB for statistics). It also hosts the "remove from app" action,
 * confirmed by a dialog before the image leaves the gallery. The metadata
 * section is pulled live from the system gallery — nothing is cached.
 *
 * Tapping the photo toggles a full-screen mode: the photo fills the viewport
 * without rounded corners and every other element (floating buttons, rating,
 * details) is hidden.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.ImageDetailsScreen(
    uri: String,
    imageDetailsViewModel: ImageDetailsViewModel,
    onBack: () -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val state by imageDetailsViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(uri) { imageDetailsViewModel.onIntent(ImageDetailsIntent.Load(uri)) }

    var fullScreen by remember { mutableStateOf(false) }
    // Sections start hidden so their enter animation plays when the screen
    // opens (AnimatedVisibility only animates on a visible-state change).
    var sectionsShown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { sectionsShown = true }

    var menuExpanded by remember { mutableStateOf(false) }
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
    val zoomState = rememberPhotoZoomState()
    val scope = rememberCoroutineScope()

    val imageAspect = state.details
        ?.let { details ->
            val w = details.width ?: return@let null
            val h = details.height ?: return@let null
            if (h > 0) w / h.toFloat() else null
        }
        ?: 1f

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val scrollState = rememberScrollState()
            // Captured so the sizes stay usable inside the nested layout lambdas.
            val viewportHeight = maxHeight
            val viewportWidth = maxWidth
            // A very tall photo would push the rating below the fold, so the
            // image height is capped and the rating below always peeks out.
            val maxImageHeight = viewportHeight * IMAGE_MAX_HEIGHT_FRACTION
            val fittedHeight =
                ((viewportWidth - IMAGE_HORIZONTAL_PADDING * 2) / imageAspect)
                    .coerceAtMost(maxImageHeight)
            val imageIsTall =
                (viewportWidth - IMAGE_HORIZONTAL_PADDING * 2) / imageAspect > maxImageHeight

            // Full-screen mode morphs the fitted card into the viewport instead
            // of jumping: height, horizontal padding and corner radius all
            // animate together.
            val animatedHeight by animateDpAsState(
                targetValue = if (fullScreen) viewportHeight else fittedHeight,
                animationSpec = tween(IMAGE_TOGGLE_ANIM_MS),
                label = "imageHeight",
            )
            val animatedHPadding by animateDpAsState(
                targetValue = if (fullScreen) 0.dp else IMAGE_HORIZONTAL_PADDING,
                animationSpec = tween(IMAGE_TOGGLE_ANIM_MS),
                label = "imageHPadding",
            )
            val animatedCorner by animateDpAsState(
                targetValue = if (fullScreen) 0.dp else IMAGE_CORNER_RADIUS,
                animationSpec = tween(IMAGE_TOGGLE_ANIM_MS),
                label = "imageCorner",
            )
            // Fit rect of the photo inside its (animated) container, fed to the
            // zoom state so panning can't reveal empty space around the image.
            val contentWidth = (viewportWidth - animatedHPadding * 2).value
            val contentHeight = animatedHeight.value
            val drawnContent = if (contentHeight <= 0f || contentWidth / contentHeight > imageAspect) {
                IntSize((contentHeight * imageAspect).toInt(), contentHeight.toInt())
            } else {
                IntSize(contentWidth.toInt(), (contentWidth / imageAspect).toInt())
            }
            LaunchedEffect(drawnContent) { zoomState.updateContentSize(drawnContent) }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(animatedHeight)
                        .padding(horizontal = animatedHPadding)
                        .sharedElement(sharedImageState, animatedVisibilityScope)
                        .clip(RoundedCornerShape(animatedCorner))
                        .then(
                            if (fullScreen) {
                                // Full-screen viewer: pinch-zoom + pan; a tap exits
                                // full screen (or resets the zoom when zoomed in).
                                Modifier.panAndZoom(
                                    state = zoomState,
                                    onTap = {
                                        if (zoomState.isZoomed()) {
                                            scope.launch { zoomState.animateTo(1f, Offset.Zero) }
                                        } else {
                                            fullScreen = false
                                        }
                                    },
                                )
                            } else {
                                // Fitted card: a plain tap enters full screen;
                                // drags keep scrolling the details column.
                                Modifier.clickable { fullScreen = true }
                            },
                        ),
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
                    enter = fadeIn(tween(SECTION_ANIM_ENTER_MS)) +
                        slideInVertically(tween(SECTION_ANIM_ENTER_MS)) { it },
                    exit = fadeOut(tween(SECTION_ANIM_EXIT_MS)) +
                        slideOutVertically(tween(SECTION_ANIM_EXIT_MS)) { it },
                ) {
                    Column {
                        // The rating tucks under the photo's bottom edge when the
                        // photo is tall, keeping the section above the fold; once
                        // the user scrolls down it settles back into place.
                        val ratingOverlap by animateDpAsState(
                            targetValue = if (imageIsTall && scrollState.value <= 0f) {
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
                            onSetScore = { score ->
                                imageDetailsViewModel.onIntent(ImageDetailsIntent.SetScore(score))
                            },
                            onRemoveClick = { showRemoveDialog = true },
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .offset(y = -ratingOverlap),
                        )
                        Spacer(Modifier.height(12.dp))
                        DetailsSection(
                            state = state,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }

            // Floating top bar: back and menu on opposite corners, over the photo.
            // Hidden during the shared-element transition (the photo renders above
            // it in the transition overlay) and in full-screen mode, then fades in.
            AnimatedVisibility(
                visible = !fullScreen && !transitionActive,
                enter = fadeIn(tween(TOP_BAR_ANIM_MS)),
                exit = fadeOut(tween(TOP_BAR_ANIM_MS / 2)),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    FloatingTopButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }
                    Box {
                        FloatingTopButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "Options",
                                tint = Color.White,
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Open in gallery") },
                                onClick = {
                                    menuExpanded = false
                                    openImageInGallery(context, uri)
                                },
                            )
                        }
                    }
                }
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
                        imageDetailsViewModel.onIntent(ImageDetailsIntent.DeleteImage)
                        onBack()
                    },
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/** Circular floating action for the top bar; a dark scrim keeps it visible on any photo. */
@Composable
private fun FloatingTopButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.padding(8.dp),
        shape = CircleShape,
        color = FLOATING_BUTTON_BACKGROUND,
    ) {
        IconButton(onClick = onClick) { content() }
    }
}

/** Opens [uri] (a MediaStore content URI) in the system gallery app. */
private fun openImageInGallery(context: Context, uri: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(uri), "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}

@Composable
private fun RatingSection(
    scores: List<Score>,
    hasUserRating: Boolean,
    uncertain: Boolean,
    onSetScore: (Int) -> Unit,
    onRemoveClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = "Rating",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            if (scores.isEmpty()) {
                Text(
                    text = "Not rated",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    scores.forEach { score ->
                        RatingStar(fraction = score.score / 5f, size = 22.dp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        hasUserRating -> "Your rating"
                        uncertain -> "Best guess — review this rating"
                        scores.size > 1 -> "${scores.size} hands rated"
                        else -> "Detected rating"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uncertain) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "Change rating",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            StarRatingSelector(
                selected = scores.maxOfOrNull { it.score } ?: 0,
                onSelect = onSetScore,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Your rating stays on top of the detected scores, which are kept for statistics.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onRemoveClick,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Remove from PhotoRate")
            }
        }
    }
}

/** Five tappable stars; picking one immediately saves that rating. */
@Composable
private fun StarRatingSelector(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..5).forEach { score ->
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable { onSelect(score) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "Rate $score",
                    tint = if (score <= selected) StarColors.filled else StarColors.empty,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
    }
}

@Composable
private fun DetailsSection(state: ImageDetailsUiState, modifier: Modifier = Modifier) {
    val details = state.details
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = "Details",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            DetailRow("Name", details?.displayName)
            DetailRow("Taken", details?.dateTakenEpochSeconds?.let(::formatDate))
            DetailRow("Added", details?.dateAddedEpochSeconds?.let(::formatDate))
            DetailRow("Modified", details?.dateModifiedEpochSeconds?.let(::formatDate))
            DetailRow("Size", details?.sizeBytes?.let(::formatBytes))
            DetailRow(
                label = "Resolution",
                value = when {
                    details?.width != null && details.height != null -> "${details.width} × ${details.height}"
                    else -> null
                },
            )
            DetailRow("Type", details?.mimeType)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * A single star whose fill shows [fraction] of its width filled (0f = empty,
 * 1f = full). Used for the per-score rating display on grid cards and details.
 * [filledColor] lets uncertain (best-guess) ratings use a distinct tone.
 */
@Composable
fun RatingStar(fraction: Float, size: Dp, modifier: Modifier = Modifier, filledColor: Color = StarColors.filled) {
    // The `size` parameter shadows DrawScope.size inside drawWithContent, so
    // the clip rect below uses an explicit `this.size` to reach the DrawScope
    // (px) size instead of the Dp parameter.
    Box(modifier.size(size)) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null,
            tint = StarColors.empty,
            modifier = Modifier.matchParentSize(),
        )
        Box(
            Modifier
                .matchParentSize()
                .drawWithContent {
                    // drawContent() is a member of this ContentDrawScope receiver,
                    // not of the plain DrawScope inside clipRect, so capture it.
                    val contentScope = this
                    clipRect(right = contentScope.size.width * fraction.coerceIn(0f, 1f)) {
                        contentScope.drawContent()
                    }
                },
        ) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = filledColor,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/**
 * Developer Mode overlay: draws the raw detected 21-point hand landmarks on
 * top of the photo, with the MediaPipe hand-skeleton edges, so the model's
 * actual output can be inspected (see FeatureFlagRepository.devModeEnabled).
 * Points are normalized 0..1 image-space, so they map directly onto the
 * photo's displayed bounds (the photo is shown uncropped in dev mode).
 */
@Composable
private fun LandmarkOverlay(hands: List<List<Point>>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        hands.forEach { hand ->
            val screen = hand.map { point ->
                Offset(point.x * size.width, point.y * size.height)
            }
            // Skeleton edges (MediaPipe hand connections, index-based).
            HAND_SKELETON.forEach { (from, to) ->
                if (from < screen.size && to < screen.size) {
                    drawLine(
                        color = DEV_LANDMARK_COLOR,
                        start = screen[from],
                        end = screen[to],
                        strokeWidth = DEV_SKELETON_WIDTH.toPx(),
                    )
                }
            }
            screen.forEach { offset ->
                drawCircle(
                    color = DEV_LANDMARK_COLOR,
                    radius = DEV_JOINT_RADIUS.toPx(),
                    center = offset,
                )
            }
        }
    }
}

/**
 * A row of rating stars (one per distinct score), lightly scrimmed for contrast
 * on photos. Pass [filledColor] to distinguish uncertain (best-guess) ratings.
 */
@Composable
fun RatingStarsOverlay(scores: List<Score>, modifier: Modifier = Modifier, filledColor: Color = StarColors.filled) {
    if (scores.isEmpty()) return
    Row(
        modifier = modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(50))
            .background(OVERLAY_SCRIM)
            .padding(horizontal = 7.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        scores.sortedBy { it.score }.forEach { score ->
            RatingStar(fraction = score.score / 5f, size = 15.dp, filledColor = filledColor)
        }
    }
}

internal object StarColors {
    val filled = Color(0xFFFFC107)
    val empty = Color(0xFF8A8A8A)

    /** Best-guess ratings (uncertain tier) use this tone instead of the confident amber. */
    val uncertain = Color(0xFFFF7043)
}

/** High-contrast cyan for the dev-mode landmark overlay (visible on any photo). */
private val DEV_LANDMARK_COLOR = Color(0xFF00E5FF)
private val DEV_SKELETON_WIDTH = 3.dp
private val DEV_JOINT_RADIUS = 6.dp

/** MediaPipe 21-point hand skeleton edges (0..20). */
private val HAND_SKELETON = listOf(
    0 to 1, 1 to 2, 2 to 3, 3 to 4, // thumb
    0 to 5, 5 to 6, 6 to 7, 7 to 8, // index
    5 to 9, 9 to 10, 10 to 11, 11 to 12, // middle
    9 to 13, 13 to 14, 14 to 15, 15 to 16, // ring
    13 to 17, 17 to 18, 18 to 19, 19 to 20, // pinky
    0 to 17, // palm base
)

private val OVERLAY_SCRIM = Color(0x59000000) // 35% black

/** Fraction of the viewport the details photo may occupy before the rating below is allowed to overlap it. */
private const val IMAGE_MAX_HEIGHT_FRACTION = 0.62f

/** Same corner radius as the gallery grid cards, so the shared element looks identical. */
private val IMAGE_CORNER_RADIUS = 24.dp
private val IMAGE_HORIZONTAL_PADDING = 16.dp

/** How far the rating card tucks under a tall photo, keeping it above the fold. */
private val RATING_OVERLAP = 24.dp

/** 80% black scrim so the floating buttons stay visible over any photo. */
private val FLOATING_BUTTON_BACKGROUND = Color(0xCC000000)
private const val SECTION_ANIM_ENTER_MS = 280
private const val SECTION_ANIM_EXIT_MS = 220

/** Duration of the fitted → full-screen image morph (and back). */
private const val IMAGE_TOGGLE_ANIM_MS = 280

/** Fade-in duration for the floating top bar after the shared transition lands. */
private const val TOP_BAR_ANIM_MS = 220

private fun formatDate(epochSeconds: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochSeconds * 1000))

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(Locale.US, bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.1f KB".format(Locale.US, bytes / 1_024.0)
    else -> "$bytes B"
}
