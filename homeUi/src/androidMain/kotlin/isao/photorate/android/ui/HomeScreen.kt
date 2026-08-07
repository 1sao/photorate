package isao.photorate.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.ExpandedFullScreenContainedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberContainedSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import isao.photorate.galleryRepository.GalleryStatusCounts
import isao.photorate.galleryUi.GalleryUiState
import isao.photorate.galleryUi.GalleryUiState.ImageSorting
import isao.photorate.galleryUi.GalleryUiState.ImagesState
import isao.photorate.galleryUi.GalleryUiState.PermissionState as GalleryPermissionState
import isao.photorate.homeUi.HomeIntent
import isao.photorate.homeUi.HomeScreenUiState
import isao.photorate.homeUi.HomeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalPermissionsApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.HomeScreen(
  homeViewModel: HomeViewModel,
  animatedVisibilityScope: AnimatedVisibilityScope,
  onOpenSettings: () -> Unit,
  onOpenAppSettings: () -> Unit,
  onOpenImage: (String) -> Unit,
  onIntent: (HomeIntent) -> Unit,
) {
  val screenState by homeViewModel.uiState.collectAsStateWithLifecycle()
  val galleryPermissionState = rememberPermissionState(GALLERY_PERMISSION)

  HomeScreenContent(
    state = screenState,
    permissionState = galleryPermissionState,
    animatedVisibilityScope = animatedVisibilityScope,
    onOpenSettings = onOpenSettings,
    onOpenAppSettings = onOpenAppSettings,
    onOpenImage = onOpenImage,
    onIntent = onIntent,
  )
}

@OptIn(
  ExperimentalMaterial3Api::class,
  ExperimentalPermissionsApi::class,
  ExperimentalSharedTransitionApi::class,
)
@Composable
fun SharedTransitionScope.HomeScreenContent(
  state: HomeScreenUiState,
  permissionState: PermissionState,
  animatedVisibilityScope: AnimatedVisibilityScope,
  onOpenSettings: () -> Unit,
  onOpenAppSettings: () -> Unit,
  onOpenImage: (String) -> Unit,
  onIntent: (HomeIntent) -> Unit,
) {
  val galleryState = state.gallery
  val searchState = state.search

  var showRationaleDialog by remember { mutableStateOf(false) }
  val status = permissionState.status
  val isGranted = status is PermissionStatus.Granted
  val denied = status as? PermissionStatus.Denied

  val scrollBehavior = SearchBarDefaults.enterAlwaysSearchBarScrollBehavior()

  LaunchedEffect(isGranted) { if (isGranted) onIntent(HomeIntent.Rescan) }

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

  val searchBarState = rememberContainedSearchBarState()
  val textFieldState = rememberTextFieldState(searchState.query)
  val scope = rememberCoroutineScope()
  val appBarWithSearchColors =
    SearchBarDefaults.appBarWithSearchColors(
      searchBarColors = SearchBarDefaults.containedColors(state = searchBarState)
    )
  LaunchedEffect(searchState.query) {
    if (textFieldState.text.toString() != searchState.query) {
      textFieldState.edit { replace(0, length, searchState.query) }
    }
  }
  LaunchedEffect(textFieldState) {
    snapshotFlow { textFieldState.text.toString() }
      .collect { text -> onIntent(HomeIntent.UpdateSearchQuery(text)) }
  }
  val inputField: @Composable () -> Unit = {
    SearchBarDefaults.InputField(
      textFieldState = textFieldState,
      searchBarState = searchBarState,
      colors = appBarWithSearchColors.searchBarColors.inputFieldColors,
      modifier = Modifier.expandSearchBarOnTap(searchBarState, scope),
      onSearch = { text ->
        onIntent(HomeIntent.UpdateSearchQuery(text))
        onIntent(HomeIntent.SubmitSearch)
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
          IconButton(onClick = { onIntent(HomeIntent.ClearSearch) }) {
            Icon(Icons.Filled.Close, contentDescription = "Clear search")
          }
        }
      },
    )
  }

  val gridState = rememberLazyGridState()
  val gallerySections =
    remember(
      galleryState.imagesState.detections,
      galleryState.imagesState.uncertainDetections,
      galleryState.imagesState.sorting,
    ) {
      buildIndexSections(
        detections = galleryState.imagesState.detections,
        uncertainDetections = galleryState.imagesState.uncertainDetections,
        sorting = galleryState.imagesState.sorting,
      )
    }
  val galleryLabelAt =
    remember(
      galleryState.imagesState.detections,
      galleryState.imagesState.uncertainDetections,
      galleryState.imagesState.sorting,
    ) {
      { index: Int ->
        indexLabel(
          index = index,
          detections = galleryState.imagesState.detections,
          uncertainDetections = galleryState.imagesState.uncertainDetections,
          sorting = galleryState.imagesState.sorting,
        )
      }
    }

  Scaffold(
    modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
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
            onIntent(HomeIntent.SelectRecentSearch(query))
            scope.launch { searchBarState.animateToCollapsed() }
          },
          onMinSimilarityChange = { value -> onIntent(HomeIntent.SetMinSimilarity(value)) },
        )
      }
    },
  ) { innerPadding ->
    Box(Modifier.fillMaxSize()) {
      GalleryGridContent(
        detections = galleryState.imagesState.detections,
        uncertainDetections = galleryState.imagesState.uncertainDetections,
        status = galleryState.imagesState.status,
        gridState = gridState,
        searchUris = galleryState.imagesState.searchUris,
        isSearching = searchState.isSearching,
        searchQuery = searchState.query,
        permissionState = permissionState,
        onOpenAppSettings = onOpenAppSettings,
        onOpenImage = onOpenImage,
        onAcceptUncertain = { uri, score ->
          onIntent(
            HomeIntent.AcceptUncertain(
              uri,
              score,
            )
          )
        },
        onDeleteUncertain = { uri -> onIntent(HomeIntent.DeleteUncertain(uri)) },
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
      text = {
        Text("PhotoRate scans your gallery to find photos with hands. Grant access to continue.")
      },
      confirmButton = { TextButton(onClick = confirmRationale) { Text("Grant access") } },
      dismissButton = { TextButton(onClick = dismissRationale) { Text("Not now") } },
    )
  }
}

private fun Modifier.expandSearchBarOnTap(
  searchBarState: SearchBarState,
  scope: CoroutineScope,
): Modifier =
  pointerInput(searchBarState) {
    awaitEachGesture {
      awaitFirstDown(requireUnconsumed = false)
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
private fun HomeScreenPreview() {
  MaterialTheme {
    SharedTransitionLayout {
      AnimatedContent(targetState = true) { _ ->
        HomeScreenContent(
          state =
            HomeScreenUiState(
              gallery =
                GalleryUiState(
                  permissionState = GalleryPermissionState.Denied,
                  imagesState =
                    ImagesState(
                      status = GalleryStatusCounts(emptyMap()),
                      detections = emptyList(),
                      uncertainDetections = emptyList(),
                      sorting = ImageSorting.Date(isAscending = false),
                    ),
                )
            ),
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
