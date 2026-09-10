package isao.photorate.homeUi

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import isao.photorate.coreUi.composable.PhotoRatePreview
import isao.photorate.coreUi.composable.StatusBarBackground
import isao.photorate.gallery.db.GalleryImageStatus.DONE
import isao.photorate.gallery.db.GalleryImageStatus.PENDING
import isao.photorate.gallery.db.GalleryImageStatus.PROCESSING
import isao.photorate.galleryRepository.GalleryStatusCounts
import isao.photorate.galleryUi.GALLERY_PERMISSION
import isao.photorate.galleryUi.GalleryImageItem
import isao.photorate.galleryUi.GalleryIntent.AcceptUncertain
import isao.photorate.galleryUi.GalleryIntent.DeleteUncertain
import isao.photorate.galleryUi.GalleryItem
import isao.photorate.galleryUi.GalleryPermissionCard
import isao.photorate.galleryUi.GalleryUiState
import isao.photorate.galleryUi.GalleryUiState.ImagesState
import isao.photorate.galleryUi.GridCellsAdaptiveEvenOnly
import isao.photorate.galleryUi.NoMatchesCard
import isao.photorate.galleryUi.ScanStatusCard
import isao.photorate.galleryUi.UncertainGalleryItem
import isao.photorate.imageRecognition.classify.Score
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalPermissionsApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
  viewModel: HomeViewModel = koinViewModel(),
  onOpenSettings: () -> Unit,
  onOpenImage: (String) -> Unit,
) {
  val screenState by viewModel.uiState.collectAsStateWithLifecycle()
  val galleryPermissionState = rememberPermissionState(GALLERY_PERMISSION)

  HomeScreenContent(
    state = screenState,
    permissionState = galleryPermissionState,
    onOpenSettings = onOpenSettings,
    onOpenImage = onOpenImage,
    onIntent = viewModel::onIntent,
  )
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeScreenContent(
  state: HomeScreenUiState,
  permissionState: PermissionState,
  onOpenSettings: () -> Unit,
  onOpenImage: (String) -> Unit,
  onIntent: (HomeIntent) -> Unit,
) {
  val scrollBehavior = SearchBarDefaults.enterAlwaysSearchBarScrollBehavior()

  val gridState = rememberLazyGridState()

  Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
      modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
      topBar = {
        TopBar(
          state = state,
          scrollBehavior = scrollBehavior,
          onOpenSettings = onOpenSettings,
          onIntent = onIntent,
        )
      },
    ) { innerPadding ->
      GalleryGridContent(
        state = state,
        permissionState = permissionState,
        contentPadding = innerPadding,
        onIntent = onIntent,
        onOpenImage = onOpenImage,
      )
    }

    StatusBarBackground(
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun GalleryGridContent(
  state: HomeScreenUiState,
  permissionState: PermissionState,
  contentPadding: PaddingValues,
  onIntent: (HomeIntent) -> Unit,
  onOpenImage: (String) -> Unit,
) {
  LaunchedEffect(permissionState.status.isGranted) { onIntent(HomeIntent.GalleryPermissionGranted) }

  val isSearching = state.search.queryResults != null
  //    || state.search.pendingQuery.isInProgress
  val displayedImages =
    remember(state.search.queryResults, state.gallery.imagesState.detections) {
      val allImages = state.gallery.imagesState.detections
      val foundImageUris = state.search.queryResults?.result ?: return@remember allImages
      foundImageUris.mapNotNull { foundImageUri ->
        allImages.firstOrNull { it.uri == foundImageUri }
      }
    }
  val displayedUncertainImages =
    remember(state.search.queryResults, state.gallery.imagesState.uncertainDetections) {
      val allImages = state.gallery.imagesState.uncertainDetections
      val foundImageUris = state.search.queryResults?.result ?: return@remember allImages
      foundImageUris.mapNotNull { foundImageUri ->
        allImages.firstOrNull { it.uri == foundImageUri }
      }
    }

  // TODO tweak how grid preloads items to fix image blinking on load
  // TODO ensure new item types start from a new row by adding a spacer with the remaining column
  //  span between them
  LazyVerticalGrid(
    state = rememberLazyGridState(),
    modifier = Modifier.fillMaxSize(),
    columns = GridCellsAdaptiveEvenOnly(160.dp),
    contentPadding =
      contentPadding +
        PaddingValues(
          start = 8.dp,
          end = 8.dp,
          top = 6.dp,
          bottom = 32.dp,
        ),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    if (!permissionState.status.isGranted && !isSearching) {
      item("permission", span = { GridItemSpan(maxLineSpan) }) {
        GalleryPermissionCard(
          permissionState = permissionState,
          modifier = Modifier.animateItem(),
        )
      }
    }

    if (isSearching && displayedImages.isEmpty() && displayedUncertainImages.isEmpty()) {
      item("no-matches", span = { GridItemSpan(maxLineSpan) }) {
        NoMatchesCard(query = state.search.queryResults?.value ?: "")
      }
    }

    if (!isSearching) {
      item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          ScanStatusCard(
            statusCounts = state.gallery.imagesState.status,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
          )
        }
      }
    }

    items(displayedImages, key = { it.uri }) { item ->
      GalleryItem(
        item = item,
        onClick = remember(item.uri) { { onOpenImage(item.uri) } },
      )
    }

    items(
      items = displayedUncertainImages,
      key = { "uncertain_${it.uri}" },
      span = { GridItemSpan(2) },
    ) { item ->
      UncertainGalleryItem(
        modifier = Modifier.animateItem(),
        item = item,
        onClick = { onOpenImage(item.uri) },
        onAccept = { uri: String, score: Int ->
          onIntent(HomeIntent.Gallery(AcceptUncertain(uri, score)))
        },
        onDelete = { uri: String -> onIntent(HomeIntent.Gallery(DeleteUncertain(uri))) },
      )
    }
  }
}

@OptIn(ExperimentalPermissionsApi::class)
@Preview
@Composable
private fun HomeScreenPreview() {
  PhotoRatePreview {
    HomeScreenContent(
      state =
        HomeScreenUiState(
          gallery =
            GalleryUiState(
              imagesState =
                ImagesState(
                  status =
                    GalleryStatusCounts(
                      mapOf(PENDING to 12L, PROCESSING to 3L, DONE to 98L),
                    ),
                  detections =
                    listOf(
                      GalleryImageItem("content://preview/a", listOf(Score.FIVE)),
                      GalleryImageItem("content://preview/b", listOf(Score.ONE)),
                      GalleryImageItem("content://preview/c", listOf(Score.FOUR)),
                      GalleryImageItem("content://preview/d", listOf(Score.TWO)),
                    ),
                  uncertainDetections =
                    listOf(
                      GalleryImageItem("content://preview/u1", listOf(Score.THREE)),
                      GalleryImageItem("content://preview/u2", listOf(Score.FOUR)),
                    ),
                ),
            ),
        ),
      permissionState = previewPermissionState(isGranted = true),
      onOpenSettings = {},
      onOpenImage = {},
      onIntent = {},
    )
  }
}

@Preview(name = "Empty (permission needed)")
@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun GalleryGridContentEmptyPreview() {
  PhotoRatePreview {
    HomeScreenContent(
      state = HomeScreenUiState(),
      permissionState = previewPermissionState(isGranted = false),
      onOpenImage = {},
      onOpenSettings = {},
      onIntent = {},
    )
  }
}

@OptIn(ExperimentalPermissionsApi::class)
private fun previewPermissionState(isGranted: Boolean): PermissionState =
  object : PermissionState {
    override val permission: String = GALLERY_PERMISSION
    override val status: PermissionStatus =
      if (isGranted) PermissionStatus.Granted
      else PermissionStatus.Denied(shouldShowRationale = false)

    override fun launchPermissionRequest() = Unit
  }
