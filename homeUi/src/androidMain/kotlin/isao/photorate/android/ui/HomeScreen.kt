package isao.photorate.android.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.coreui.composable.PhotoRatePreview
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.rememberPermissionState
import isao.photorate.galleryRepository.GalleryStatusCounts
import isao.photorate.galleryUi.GalleryUiState
import isao.photorate.galleryUi.GalleryUiState.ImageSorting
import isao.photorate.galleryUi.GalleryUiState.ImagesState
import isao.photorate.galleryUi.GalleryUiState.PermissionState as GalleryPermissionState
import isao.photorate.homeUi.HomeIntent
import isao.photorate.homeUi.HomeScreenUiState
import isao.photorate.homeUi.HomeViewModel
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalPermissionsApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
  viewModel: HomeViewModel = koinViewModel(),
  onOpenSettings: () -> Unit,
  onOpenAppSettings: () -> Unit,
  onOpenImage: (String) -> Unit,
) {
  val screenState by viewModel.uiState.collectAsStateWithLifecycle()
  val galleryPermissionState = rememberPermissionState(GALLERY_PERMISSION)

  HomeScreenContent(
    state = screenState,
    permissionState = galleryPermissionState,
    onOpenSettings = onOpenSettings,
    onOpenAppSettings = onOpenAppSettings, // TODO rework navigation, support multi-module
    onOpenImage = onOpenImage,
    onIntent = viewModel::onIntent,
  )
}

@OptIn(
  ExperimentalMaterial3Api::class,
  ExperimentalPermissionsApi::class,
  ExperimentalSharedTransitionApi::class,
)
@Composable
fun HomeScreenContent(
  state: HomeScreenUiState,
  permissionState: PermissionState,
  onOpenSettings: () -> Unit,
  onOpenAppSettings: () -> Unit,
  onOpenImage: (String) -> Unit,
  onIntent: (HomeIntent) -> Unit,
) {
  val galleryState = state.gallery
  val searchState = state.search

  val scrollBehavior = SearchBarDefaults.enterAlwaysSearchBarScrollBehavior()

  val gridState = rememberLazyGridState()
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
      state = galleryState,
      gridState = gridState,
      permissionState = permissionState,
      modifier = Modifier.fillMaxSize(),
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
      contentPadding = innerPadding,
    )
  }
}

@OptIn(ExperimentalPermissionsApi::class)
@Preview(showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun HomeScreenPreview() {
  PhotoRatePreview {
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
      onOpenSettings = {},
      onOpenAppSettings = {},
      onOpenImage = {},
      onIntent = {},
    )
  }
}
