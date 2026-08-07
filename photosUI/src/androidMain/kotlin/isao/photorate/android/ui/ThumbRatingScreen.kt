package isao.photorate.android.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExpandedFullScreenContainedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberContainedSearchBarState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import isao.photorate.db.SelectUncertainImagesWithScore
import isao.photorate.galleryRepository.GalleryStatusCounts
import isao.photorate.galleryUi.GalleryImageItem
import isao.photorate.galleryUi.GalleryIntent
import isao.photorate.galleryUi.GalleryUiState
import isao.photorate.galleryUi.GalleryUiState.ImageSorting
import isao.photorate.galleryUi.GalleryUiState.ImagesState
import isao.photorate.galleryUi.GalleryUiState.PermissionState as GalleryPermissionState
import isao.photorate.galleryUi.GalleryViewModel
import isao.photorate.galleryUi.SearchUiState
import isao.photorate.inference.classify.Score
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The gallery grid, used as the Gallery route's content inside the Navigation3
 * host. The shared-element transition to the details screen lives in the host
 * (one [SharedTransitionLayout] around the whole NavDisplay), so this screen is
 * just the grid + search bar. Tapping the search bar expands it full screen
 * (M3 Expressive [ExpandedFullScreenContainedSearchBar]) to enter a query;
 * submitting collapses it and this grid filters to the results, so search
 * results live in the same main list.
 */
@OptIn(ExperimentalPermissionsApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.ThumbRatingScreen(
    galleryViewModel: GalleryViewModel,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onOpenSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenImage: (String) -> Unit,
    onIntent: (GalleryIntent) -> Unit,
) {
    val screenState by galleryViewModel.uiState.collectAsStateWithLifecycle()
    val galleryPermissionState = rememberPermissionState(GALLERY_PERMISSION)

    ThumbRatingScreenContent(
        state = screenState.gallery,
        searchState = screenState.search,
        permissionState = galleryPermissionState,
        animatedVisibilityScope = animatedVisibilityScope,
        onOpenSettings = onOpenSettings,
        onOpenAppSettings = onOpenAppSettings,
        onOpenImage = onOpenImage,
        onIntent = onIntent,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.ThumbRatingScreenContent(
    state: GalleryUiState,
    searchState: SearchUiState,
    permissionState: PermissionState,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onOpenSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenImage: (String) -> Unit,
    onIntent: (GalleryIntent) -> Unit,
) {
    var showRationaleDialog by remember { mutableStateOf(false) }
    val status = permissionState.status
    val isGranted = status is PermissionStatus.Granted
    val denied = status as? PermissionStatus.Denied

    // The floating search bar hides while scrolling down and reappears on even
    // a slight scroll up (M3 "enter always" behavior).
    val scrollBehavior = SearchBarDefaults.enterAlwaysSearchBarScrollBehavior()

    // Kick off the scan once photos access is granted. `rescan()` also runs on
    // init (no permission knowledge in the ViewModel), so this only adds work on
    // the denied -> granted transition; the duplicate call at startup is cheap
    // because populate+landmark are idempotent for already-DONE rows.
    LaunchedEffect(isGranted) {
        if (isGranted) onIntent(GalleryIntent.Rescan)
    }

    val requestPermission: () -> Unit = {
        if (denied?.shouldShowRationale == true) {
            showRationaleDialog = true
        } else {
            permissionState.launchPermissionRequest()
        }
    }
    val confirmRationale: () -> Unit = {
        showRationaleDialog = false
        permissionState.launchPermissionRequest()
    }
    val dismissRationale: () -> Unit = { showRationaleDialog = false }

    // Shared between the collapsed top-bar field and the expanded full-screen
    // search bar, so the pill morphs into the search screen (M3 Expressive).
    val searchBarState = rememberContainedSearchBarState()
    val textFieldState = rememberTextFieldState(searchState.query)
    val scope = rememberCoroutineScope()
    val appBarWithSearchColors = SearchBarDefaults.appBarWithSearchColors(
        searchBarColors = SearchBarDefaults.containedColors(state = searchBarState),
    )
    // Keep the M3 text-field state in sync with the ViewModel query (which is
    // also the source of truth for the gallery's filtered grid).
    LaunchedEffect(searchState.query) {
        if (textFieldState.text.toString() != searchState.query) {
            textFieldState.edit { replace(0, length, searchState.query) }
        }
    }
    // Push user typing into the ViewModel so the submitted search and the
    // gallery filter both use the latest text.
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collect { text ->
            onIntent(GalleryIntent.UpdateSearchQuery(text))
        }
    }
    val inputField: @Composable () -> Unit = {
        SearchBarDefaults.InputField(
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            colors = appBarWithSearchColors.searchBarColors.inputFieldColors,
            // M3 expands on input-field focus/click internally, but that wiring
            // can be missed when the collapsed pill is tapped while unfocused;
            // observing the tap (without consuming it, so the field still gains
            // focus) guarantees the pill→full-screen morph always plays.
            modifier = Modifier.expandSearchBarOnTap(searchBarState, scope),
            onSearch = { text ->
                // The IME action can race the async text sync, so submit the
                // exact text the field currently holds.
                onIntent(GalleryIntent.UpdateSearchQuery(text))
                onIntent(GalleryIntent.SubmitSearch)
                scope.launch { searchBarState.animateToCollapsed() }
            },
            placeholder = { Text("Search your photos…") },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                if (textFieldState.text.isNotEmpty()) {
                    IconButton(onClick = { onIntent(GalleryIntent.ClearSearch) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
        )
    }

    val gridState = rememberLazyGridState()
    // Index-scrollbar sections: which group each grid position belongs to. The
    // scan-status card is grid item 0 and is never part of a section, so scan
    // status never appears in any scrollbar pill. Only meaningful for the plain
    // gallery (search results are ranked by similarity, not grouped).
    val gallerySections = remember(state.imagesState.detections, state.imagesState.uncertainDetections, state.imagesState.sorting) {
        buildIndexSections(
            detections = state.imagesState.detections,
            uncertainDetections = state.imagesState.uncertainDetections,
            sorting = state.imagesState.sorting,
        )
    }
    val galleryLabelAt = remember(state.imagesState.detections, state.imagesState.uncertainDetections, state.imagesState.sorting) {
        { index: Int ->
            indexLabel(
                index = index,
                detections = state.imagesState.detections,
                uncertainDetections = state.imagesState.uncertainDetections,
                sorting = state.imagesState.sorting,
            )
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppBarWithSearch(
                state = searchBarState,
                scrollBehavior = scrollBehavior,
                colors = appBarWithSearchColors,
                inputField = inputField,
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
            ExpandedFullScreenContainedSearchBar(
                state = searchBarState,
                inputField = inputField,
                colors = appBarWithSearchColors.searchBarColors,
            ) {
                SearchContent(
                    state = searchState,
                    onSelectRecent = { query ->
                        onIntent(GalleryIntent.SelectRecentSearch(query))
                        scope.launch { searchBarState.animateToCollapsed() }
                    },
                    onMinSimilarityChange = { value -> onIntent(GalleryIntent.SetMinSimilarity(value)) },
                )
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize()) {
            GalleryGridContent(
                // Granular params instead of the whole state: scan-status ticks
                // only change `status`, so the grid items (which read only the
                // lists) skip recomposition instead of re-composing every cell.
                detections = state.imagesState.detections,
                uncertainDetections = state.imagesState.uncertainDetections,
                status = state.imagesState.status,
                gridState = gridState,
                searchState = searchState,
                permissionState = permissionState,
                onOpenAppSettings = onOpenAppSettings,
                onOpenImage = onOpenImage,
                onIntent = onIntent,
                animatedVisibilityScope = animatedVisibilityScope,
                contentPadding = innerPadding,
            )
            if (searchState.query.isBlank()) {
                IndexScrollbar(
                    gridState = gridState,
                    sections = gallerySections,
                    labelAt = galleryLabelAt,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (showRationaleDialog) {
        AlertDialog(
            onDismissRequest = dismissRationale,
            title = { Text("Photos access needed") },
            text = { Text("PhotoRate scans your gallery to find photos with hands. Grant access to continue.") },
            confirmButton = {
                TextButton(onClick = confirmRationale) { Text("Grant access") }
            },
            dismissButton = {
                TextButton(onClick = dismissRationale) { Text("Not now") }
            },
        )
    }
}

/**
 * The main gallery grid: permission card, scan status, rated images and the
 * uncertain section. When a search query is active the grid filters down to the
 * search results (confident + uncertain matches) so filtered images appear in
 * the same, main gallery list.
 */
@OptIn(ExperimentalPermissionsApi::class, ExperimentalSharedTransitionApi::class, ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
private fun SharedTransitionScope.GalleryGridContent(
    detections: List<GalleryImageItem>,
    uncertainDetections: List<SelectUncertainImagesWithScore>,
    status: GalleryStatusCounts,
    gridState: LazyGridState,
    searchState: SearchUiState,
    permissionState: PermissionState,
    onOpenAppSettings: () -> Unit,
    onOpenImage: (String) -> Unit,
    onIntent: (GalleryIntent) -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
    contentPadding: PaddingValues,
) {
    val searchActive = searchState.query.isNotBlank()
    val resultUris = remember(searchState.results) { searchState.results.map { it.uri }.toSet() }
    val visibleDetections =
        if (searchActive) {
            detections.filter { it.uri in resultUris }
        } else {
            detections
        }
    val visibleUncertainDetections =
        if (searchActive) {
            uncertainDetections.filter { it.uri in resultUris }
        } else {
            uncertainDetections
        }
    // Search ranks every hand image, including ones whose hands fall outside
    // the config score filters (so they're in neither grid list). Render those
    // as plain cards so a real match is never silently missing.

    //TODO drop?
    val leftoverResults =
        if (searchActive) {
            val covered = (visibleDetections.map { it.uri } + visibleUncertainDetections.map { it.uri }).toSet()
            searchState.results.filter { it.uri !in covered }
        } else {
            emptyList()
        }

    //TODO use rememberWindowSizeClass
    val windowSizeClass = LocalActivity.current?.let { activity -> calculateWindowSizeClass(activity) }
    val isTablet = remember(windowSizeClass) {
        when (windowSizeClass?.widthSizeClass) {
            WindowWidthSizeClass.Medium, WindowWidthSizeClass.Expanded -> true
            else -> false
        }
    }

    LazyVerticalGrid(
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = 32.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!permissionState.status.isGranted && !searchActive) {
            item("permission", span = { GridItemSpan(maxLineSpan) }) {
                GalleryPermissionCard(
                    permissionState = permissionState,
                    onOpenAppSettings = onOpenAppSettings,
                    modifier = Modifier.animateItem(),
                )
            }
        }

        when {
            searchActive && searchState.isSearching -> {
                item("search", span = { GridItemSpan(maxLineSpan) }) {
                    SearchingIndicator(query = searchState.query)
                }
            }

            searchActive && searchState.results.isEmpty() -> {
                item("no-matches", span = { GridItemSpan(maxLineSpan) }) {
                    NoMatchesCard(query = searchState.query)
                }
            }

            else -> {
                if (searchActive && visibleDetections.isNotEmpty()) {
                    item(key = "search-result", span = { GridItemSpan(maxLineSpan) }) {
                        SearchResultsHeader(
                            query = searchState.query,
                            count = visibleDetections.size + visibleUncertainDetections.size,
                        )
                    }
                }
                if (!searchActive) {
                    item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
                        ScanStatusCard(status)
                    }
                }
                items(visibleDetections, key = { it.uri }) { item ->
                    GalleryImageCard(
                        item = item,
                        // Stable per-item lambda: a freshly created closure would
                        // recompose every visible cell on any state change.
                        onClick = remember(item.uri) { { onOpenImage(item.uri) } },
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                }
                //TODO likely isn't needed
                // if (leftoverResults.isNotEmpty()) {
                //     items(leftoverResults, key = { it.uri }) { image ->
                //         GalleryImageCard(
                //             item = remember(image.uri) {
                //                 GalleryImageItem(uri = image.uri, scores = emptyList())
                //             },
                //             onClick = remember(image.uri) { { onOpenImage(image.uri) } },
                //             animatedVisibilityScope = animatedVisibilityScope,
                //         )
                //     }
                // }

                // Low-confidence (uncertain) detections, shown in their own
                // section so the user can review the best-guess scores. Search
                // results include them too, per the search design.
                if (visibleUncertainDetections.isNotEmpty()) {
                    item(
                        key = "uncertain-header",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        UncertainSectionHeader(count = visibleUncertainDetections.size)
                    }
                    items(
                        items = visibleUncertainDetections,
                        key = { "uncertain_${it.uri}" },
                        span = {
                            GridItemSpan(if (isTablet) maxLineSpan / 2 else maxLineSpan)
                        },
                    ) { image ->
                        UncertainImageCard(
                            // Fade in/out so accepting or deleting a guess
                            // animates the card away instead of popping it.
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(200),
                                fadeOutSpec = tween(250),
                            ),
                            uri = image.uri,
                            bestGuessScore = image.bestGuessScore,
                            onClick = remember(image.uri) { { onOpenImage(image.uri) } },
                            onAccept = remember {
                                { uri: String, score: Int ->
                                    onIntent(GalleryIntent.AcceptUncertain(uri, score))
                                }
                            },
                            onDelete = remember { { uri: String -> onIntent(GalleryIntent.DeleteUncertain(uri)) } },
                            animatedVisibilityScope = animatedVisibilityScope,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchingIndicator(query: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.height(24.dp).width(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Searching for “$query”…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchResultsHeader(query: String, count: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = "$count result${if (count == 1) "" else "s"} for “$query”",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun NoMatchesCard(query: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = "No matches for “$query”",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Try a different word or lower the similarity threshold in Settings → Developer Mode.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A rated image in the main grid. Shows the image with a rating-star badge
 * (one star per distinct score, filled by the score) and opens the details
 * screen via a shared-element transition on the photo.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.GalleryImageCard(
    item: GalleryImageItem,
    onClick: () -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val sharedState = rememberSharedContentState(key = "image_${item.uri}")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .sharedElement(sharedState, animatedVisibilityScope)
                .clip(RoundedCornerShape(24.dp)),
        )
        RatingStarsOverlay(
            scores = item.scores,
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}

/** Section heading for the low-confidence detections. */
@Composable
private fun UncertainSectionHeader(count: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Best guesses \uD83E\uDD14",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "$count image${if (count == 1) "" else "s"} were rated with low " +
                    "confidence. Scores shown are guesses and may be wrong.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A card for a low-confidence detection. A horizontal layout: the photo on the
 * left (same square shape as grid items, shared element into details), and on
 * the right the best-guess score as a preselectable star row plus two actions:
 * a tick to accept (saves the selected score as a confident user rating, so
 * the image joins the main grid) and a cross to delete (drops the detections
 * and marks the image no-hand, like most scanned images). Tapping the photo
 * opens the details screen to review/correct the score.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.UncertainImageCard(
    uri: String,
    bestGuessScore: Score?,
    onClick: () -> Unit,
    onAccept: (String, Int) -> Unit,
    onDelete: (String) -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    var selected by remember(uri) { mutableStateOf(bestGuessScore?.score ?: DEFAULT_GUESS_SCORE) }
    val sharedState = rememberSharedContentState(key = "image_$uri")
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.fillMaxWidth()) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .sharedElement(sharedState, animatedVisibilityScope)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(onClick = onClick),
            )
            Column(
                modifier = Modifier
                    .weight(1.4f)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Best guess",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                UncertainStarSelector(
                    selected = selected,
                    onSelect = { selected = it },
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledIconButton(onClick = { onAccept(uri, selected) }) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Accept score $selected",
                        )
                    }
                    FilledTonalIconButton(
                        onClick = { onDelete(uri) },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Mark as no hand",
                        )
                    }
                }
            }
        }
    }
}

/** Five tappable stars (compact, for the uncertain card); picking one preselects the score. */
@Composable
private fun UncertainStarSelector(selected: Int, onSelect: (Int) -> Unit) {
    Row {
        (1..5).forEach { score ->
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { onSelect(score) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "Rate $score",
                    // Same tone as the old best-guess badge, distinct from
                    // confident amber ratings.
                    tint = if (score <= selected) StarColors.uncertain else StarColors.empty,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/** Fallback preselected score when an uncertain image somehow has no best guess. */
private const val DEFAULT_GUESS_SCORE = 3

/** Grid item index of the first photo card (after the scan-status card). */
private const val DETECTION_START_INDEX = 1

/**
 * Builds the scrollbar sections for the plain gallery: one section per group
 * (month+year for date sorting, top score for score sorting) in first-occurrence
 * order along the grid, plus a single uncertain section when best-guess images
 * are shown. The scan-status card (grid item 0) is never a section, so scan
 * status never appears in any scrollbar pill.
 */
private fun buildIndexSections(
    detections: List<GalleryImageItem>,
    uncertainDetections: List<SelectUncertainImagesWithScore>,
    sorting: ImageSorting,
): List<IndexScrollSection> {
    val sections = mutableListOf<IndexScrollSection>()
    val seen = mutableSetOf<Int>()
    // One reused Calendar instead of a fresh instance per image (large galleries).
    val calendar = Calendar.getInstance()
    detections.forEachIndexed { index, item ->
        val key: Int = when (sorting) {
            is ImageSorting.Score -> item.scores.maxOfOrNull { it.score } ?: return@forEachIndexed
            is ImageSorting.Date -> item.scannedAt?.let { yearMonthKey(it, calendar) } ?: return@forEachIndexed
        }
        if (seen.add(key)) {
            sections += IndexScrollSection(startIndex = DETECTION_START_INDEX + index)
        }
    }
    if (uncertainDetections.isNotEmpty()) {
        sections += IndexScrollSection(
            startIndex = DETECTION_START_INDEX + detections.size,
            isUncertain = true,
        )
    }
    return sections
}

/**
 * Label of the group under grid item [index] for the scrollbar chip: the
 * month+year (date sorting) or top rating (score sorting) of the item, or the
 * best guess of the uncertain item when the index is inside the uncertain
 * section. Indices before the first photo (the scan-status card) and beyond the
 * last item clamp to the nearest group.
 */
private fun indexLabel(
    index: Int,
    detections: List<GalleryImageItem>,
    uncertainDetections: List<SelectUncertainImagesWithScore>,
    sorting: ImageSorting,
): String {
    val uncertainStart = DETECTION_START_INDEX + detections.size
    val inUncertain = uncertainDetections.isNotEmpty() && index >= uncertainStart
    return if (inUncertain) {
        // Clamp so the section header (the index just before the first card)
        // shows the first item's info instead of a bare "?".
        val item = uncertainDetections.getOrNull((index - uncertainStart - 1).coerceAtLeast(0))
        when (sorting) {
            is ImageSorting.Score -> item?.bestGuessScore?.score?.let { "$it★" } ?: "?"
            is ImageSorting.Date -> item?.scannedAt?.let { formatMonthYear(it) } ?: "?"
        }
    } else {
        val clamped = (index - DETECTION_START_INDEX).coerceIn(0, (detections.size - 1).coerceAtLeast(0))
        val item = detections.getOrNull(clamped)
        when (sorting) {
            is ImageSorting.Score -> item?.scores?.maxOfOrNull { it.score }?.let { "$it★" } ?: ""
            is ImageSorting.Date -> item?.scannedAt?.let { formatMonthYear(it) } ?: ""
        }
    }
}

/** Year*100 + month (1-12): a stable, order-preserving date-group key. */
private fun yearMonthKey(epochMillis: Long, calendar: Calendar): Int {
    calendar.timeInMillis = epochMillis
    return calendar.get(Calendar.YEAR) * 100 + calendar.get(Calendar.MONTH) + 1
}

/** "Mar 2026"-style month + year label for the scrollbar chip. */
private fun formatMonthYear(epochMillis: Long): String = SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(epochMillis))

/**
 * Guarantees the collapsed search pill expands (with the M3 morph animation)
 * when tapped while unfocused. M3's input field expands on focus/click through
 * its own interaction wiring, but a tap on the collapsed field can reach the
 * text field without that wiring firing; this observer triggers the expansion
 * directly without consuming the events, so the field still takes focus and the
 * keyboard opens as usual.
 */
private fun Modifier.expandSearchBarOnTap(searchBarState: SearchBarState, scope: CoroutineScope): Modifier = pointerInput(searchBarState) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        // A clean tap only — any consumed event (drag, grid scroll) cancels it.
        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
        if (up.isConsumed) return@awaitEachGesture
        if (searchBarState.currentValue != SearchBarValue.Expanded) {
            scope.launch { searchBarState.animateToExpanded() }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalSharedTransitionApi::class)
@Preview(showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun ThumbRatingScreenPreview() {
    MaterialTheme {
        SharedTransitionLayout {
            // AnimatedContent just provides the AnimatedVisibilityScope the
            // shared-element modifiers need; the preview shows a single frame.
            AnimatedContent(targetState = true) { _ ->
                ThumbRatingScreenContent(
                    state = GalleryUiState(
                        permissionState = GalleryPermissionState.Denied,
                        imagesState = ImagesState(
                            status = GalleryStatusCounts(emptyMap()),
                            detections = emptyList(),
                            uncertainDetections = emptyList(),
                            sorting = ImageSorting.Date(isAscending = false),
                        ),
                    ),
                    searchState = SearchUiState(),
                    permissionState = rememberPermissionState(GALLERY_PERMISSION),
                    animatedVisibilityScope = this,
                    onOpenSettings = {},
                    onOpenAppSettings = {},
                    onOpenImage = {},
                    onIntent = {},
                )
            }
        }
    }
}
