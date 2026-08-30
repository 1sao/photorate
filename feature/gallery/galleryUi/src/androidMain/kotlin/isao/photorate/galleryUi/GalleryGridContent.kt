package isao.photorate.galleryUi

import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.isGranted
import isao.photorate.coreUi.composable.LocalSharedTransitionScope
import isao.photorate.coreUi.composable.PhotoRatePreview
import isao.photorate.coreUi.composable.imageSharedContentKey
import isao.photorate.gallery.db.GalleryImageStatus.DONE
import isao.photorate.gallery.db.GalleryImageStatus.PENDING
import isao.photorate.gallery.db.GalleryImageStatus.PROCESSING
import isao.photorate.galleryRepository.GalleryStatusCounts
import isao.photorate.imageRecognition.classify.Score

// Search-derived values are passed as primitives ([searchUris], [isSearching],
// [searchQuery]) instead of the search module's state class, keeping galleryUi
// free of a searchUi dependency.
@OptIn(
  ExperimentalPermissionsApi::class,
  ExperimentalSharedTransitionApi::class,
  ExperimentalMaterial3WindowSizeClassApi::class,
)
@Composable
fun GalleryGridContent(
  state: GalleryUiState,
  gridState: LazyGridState,
  permissionState: PermissionState,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues,
  onOpenImage: (String) -> Unit,
  onAcceptUncertain: (uri: String, score: Int) -> Unit,
  onDeleteUncertain: (uri: String) -> Unit,
) =
  Box(modifier.fillMaxSize()) {
    val searchActive = state.imagesState.searchUris != null
    val visibleDetections =
      state.imagesState.searchUris?.let { filter ->
        state.imagesState.detections.filter { it.uri in filter }
      } ?: state.imagesState.detections
    val visibleUncertainDetections = // TODO respect order from searchUris
      state.imagesState.searchUris?.let { filter ->
        state.imagesState.uncertainDetections.filter { it.uri in filter }
      } ?: state.imagesState.uncertainDetections
    //  val noMatches =
    //    searchActive &&
    //      !isSearching &&
    //      visibleDetections.isEmpty() &&
    //      visibleUncertainDetections.isEmpty()

    // TODO Move somewhere else? LocalWindowSizeClass?
    // TODO use https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive
    // ?
    // Previews have no Activity; fall back to the phone layout there.
    val isTablet = false
    //      LocalActivity.current?.let {
    //        calculateWindowSizeClass(it).widthSizeClass > WindowWidthSizeClass.Compact
    //      } ?: false

    // TODO tweak how grid preloads items to fix image blinking on load
    // TODO ensure new item types start from a new row by adding a spacer with the remaining column
    // span between them
    LazyVerticalGrid(
      state = gridState,
      modifier = Modifier.fillMaxSize(),
      columns = GridCellsAdaptiveEvenOnly(160.dp),
      contentPadding =
        contentPadding +
          PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 32.dp,
          ),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (!permissionState.status.isGranted && !searchActive) {
        item("permission", span = { GridItemSpan(maxLineSpan) }) {
          GalleryPermissionCard(
            permissionState = permissionState,
            modifier = Modifier.animateItem(),
          )
        }
      }

      when {
        //      searchActive && isSearching -> {
        //        item("search", span = { GridItemSpan(maxLineSpan) }) {
        //          SearchingIndicator(query = searchQuery)
        //        }
        //      }
        //
        //      noMatches -> {
        //        item("no-matches", span = { GridItemSpan(maxLineSpan) }) {
        //          NoMatchesCard(query = searchQuery)
        //        }
        //      }

        else -> {
          //        if (searchActive && visibleDetections.isNotEmpty()) {
          //          item(key = "search-result", span = { GridItemSpan(maxLineSpan) }) {
          //            SearchResultsHeader(
          //              query = searchQuery,
          //              count = visibleDetections.size + visibleUncertainDetections.size,
          //            )
          //          }
          //        }
          //        if (!searchActive) {
          item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
              ScanStatusCard(
                statusCounts = state.imagesState.status,
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
              )
            }
          }
          //        }

          items(visibleDetections, key = { it.uri }) { item ->
            GalleryItem(
              item = item,
              onClick = remember(item.uri) { { onOpenImage(item.uri) } },
            )
          }

          items(
            items = visibleUncertainDetections,
            key = { "uncertain_${it.uri}" },
            span = { GridItemSpan(2) },
          ) { item ->
            UncertainGalleryItem(
              modifier = Modifier.animateItem(),
              item = item,
              onClick = { onOpenImage(item.uri) },
              onAccept = { uri: String, score: Int -> onAcceptUncertain(uri, score) },
              onDelete = { uri: String -> onDeleteUncertain(uri) },
            )
          }
        }
      }
    }
  }

// TODO verify if the search is slow enough to require this
@Composable
private fun SearchingIndicator(query: String) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
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

// TODO consider removing
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

// TODO use
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
        text =
          "Try a different word or lower the similarity threshold in Settings → Developer Mode.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun GalleryItem(
  item: GalleryImageItem,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  Box(modifier = modifier.fillMaxWidth().aspectRatio(1f).clickable(onClick = onClick)) {
    GalleryCardImage(
      uri = item.uri,
      modifier = Modifier.fillMaxSize(),
    )
    RatingStar(
      fraction = item.scores.max().ratingStarFraction,
      modifier = Modifier.padding(12.dp).size(20.dp),
    )
  }
}

@Composable
private fun UncertainGalleryItem(
  item: GalleryImageItem,
  onClick: () -> Unit,
  onAccept: (String, Int) -> Unit,
  onDelete: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.largeIncreased,
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
      GalleryCardImage(
        uri = item.uri,
        onClick = onClick,
        modifier = Modifier.weight(1f).aspectRatio(1f),
      )

      var stars by rememberSaveable { mutableStateOf(item.scores.first().score) }
      Column(
        modifier =
          Modifier.weight(1f).fillMaxHeight().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          text = "Best guess",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RatingStarSelector(
          selected = stars,
          onSelect = { stars = it },
          modifier = Modifier.widthIn(max = 200.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilledIconButton(onClick = { onAccept(item.uri, stars) }) {
            Icon(
              Icons.Filled.Check,
              contentDescription = "Accept score $stars",
            )
          }
          FilledTonalIconButton(
            onClick = { onDelete(item.uri) },
            colors =
              IconButtonDefaults.filledTonalIconButtonColors(
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

@Composable
private fun GalleryCardImage(
  uri: String,
  modifier: Modifier = Modifier,
  clipShape: Shape = MaterialTheme.shapes.largeIncreased,
  onClick: (() -> Unit)? = null,
) {
  val imageKey = imageSharedContentKey(uri)
  val imageRequest =
    ImageRequest.Builder(LocalContext.current)
      .data(uri)
      .placeholderMemoryCacheKey(imageKey)
      .memoryCacheKey(imageKey)
      .build()

  val sharedBounds =
    with(LocalSharedTransitionScope.current) {
      Modifier.sharedBounds(
        rememberSharedContentState(key = imageKey),
        LocalNavAnimatedContentScope.current,
        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        enter = fadeIn(snap()),
        exit = ExitTransition.None,
        zIndexInOverlay = 1f,
      )
    }

  AsyncImage(
    model = imageRequest,
    contentDescription = null,
    contentScale = ContentScale.Crop,
    modifier =
      modifier.then(sharedBounds).clip(clipShape).let {
        if (onClick != null) it.clickable(onClick = onClick) else it
      },
  )
}

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
        text =
          "$count image${if (count == 1) "" else "s"} were rated with low " +
            "confidence. Scores shown are guesses and may be wrong.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@OptIn(ExperimentalPermissionsApi::class)
private fun previewPermissionState(granted: Boolean): PermissionState =
  object : PermissionState {
    override val permission: String = GALLERY_PERMISSION
    override val status: PermissionStatus =
      if (granted) PermissionStatus.Granted
      else PermissionStatus.Denied(shouldShowRationale = false)

    override fun launchPermissionRequest() = Unit
  }

@Preview(name = "Empty (permission needed)")
@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun GalleryGridContentEmptyPreview() {
  PhotoRatePreview {
    GalleryGridContent(
      state =
        GalleryUiState(
          imagesState =
            GalleryUiState.ImagesState(
              status = GalleryStatusCounts(emptyMap()),
              detections = emptyList(),
              uncertainDetections = emptyList(),
            ),
        ),
      gridState = rememberLazyGridState(),
      permissionState = previewPermissionState(granted = false),
      contentPadding = PaddingValues(0.dp),
      onOpenImage = {},
      onAcceptUncertain = { _: String, _: Int -> },
      onDeleteUncertain = { _: String -> },
    )
  }
}

@Preview(showBackground = true, widthDp = 411, name = "Loaded (certain + uncertain)")
@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun GalleryGridContentLoadedPreview() {
  PhotoRatePreview {
    GalleryGridContent(
      state =
        GalleryUiState(
          imagesState =
            GalleryUiState.ImagesState(
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
      gridState = rememberLazyGridState(),
      permissionState = previewPermissionState(granted = true),
      contentPadding = PaddingValues(0.dp),
      onOpenImage = {},
      onAcceptUncertain = { _: String, _: Int -> },
      onDeleteUncertain = { _: String -> },
    )
  }
}

@Preview
@Composable
private fun GalleryItemPreview() {
  PhotoRatePreview {
    GalleryItem(
      item = GalleryImageItem("content://preview/a", listOf(Score.FIVE)),
      onClick = {},
    )
  }
}

@Preview
@Composable
private fun UncertainGalleryItemPreview() {
  PhotoRatePreview {
    UncertainGalleryItem(
      item = GalleryImageItem("content://preview/u1", listOf(Score.THREE)),
      onClick = {},
      onAccept = { _: String, _: Int -> },
      onDelete = { _: String -> },
    )
  }
}

@Preview
@Composable
private fun UncertainSectionHeaderPreview() {
  PhotoRatePreview { UncertainSectionHeader(count = 5) }
}
