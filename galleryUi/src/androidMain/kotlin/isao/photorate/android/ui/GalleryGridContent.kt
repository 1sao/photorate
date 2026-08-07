package isao.photorate.android.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import isao.photorate.gallery.db.SelectUncertainImagesWithScore
import isao.photorate.galleryRepository.GalleryStatusCounts
import isao.photorate.galleryUi.GalleryImageItem
import isao.photorate.galleryUi.GalleryUiState.ImageSorting
import isao.photorate.inference.classify.Score
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Search-derived values are passed as primitives ([searchUris], [isSearching],
// [searchQuery]) instead of the search module's state class, keeping galleryUi
// free of a searchUi dependency.
@OptIn(
  ExperimentalPermissionsApi::class,
  ExperimentalSharedTransitionApi::class,
  ExperimentalMaterial3WindowSizeClassApi::class,
)
@Composable
fun SharedTransitionScope.GalleryGridContent(
  detections: List<GalleryImageItem>,
  uncertainDetections: List<SelectUncertainImagesWithScore>,
  status: GalleryStatusCounts,
  gridState: LazyGridState,
  searchUris: Set<String>?,
  isSearching: Boolean,
  searchQuery: String,
  permissionState: PermissionState,
  onOpenAppSettings: () -> Unit,
  onOpenImage: (String) -> Unit,
  onAcceptUncertain: (uri: String, score: Int) -> Unit,
  onDeleteUncertain: (uri: String) -> Unit,
  animatedVisibilityScope: AnimatedVisibilityScope,
  contentPadding: PaddingValues,
) {
  val searchActive = searchUris != null
  val visibleDetections =
    searchUris?.let { filter -> detections.filter { it.uri in filter } } ?: detections
  val visibleUncertainDetections =
    searchUris?.let { filter -> uncertainDetections.filter { it.uri in filter } }
      ?: uncertainDetections
  val noMatches =
    searchActive &&
      !isSearching &&
      visibleDetections.isEmpty() &&
      visibleUncertainDetections.isEmpty()

  // TODO use rememberWindowSizeClass
  val windowSizeClass =
    LocalActivity.current?.let { activity -> calculateWindowSizeClass(activity) }
  val isTablet =
    remember(windowSizeClass) {
      when (windowSizeClass?.widthSizeClass) {
        WindowWidthSizeClass.Medium,
        WindowWidthSizeClass.Expanded -> true

        else -> false
      }
    }

  LazyVerticalGrid(
    state = gridState,
    modifier = Modifier.fillMaxSize(),
    columns = GridCells.Fixed(2),
    contentPadding =
      PaddingValues(
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
      searchActive && isSearching -> {
        item("search", span = { GridItemSpan(maxLineSpan) }) {
          SearchingIndicator(query = searchQuery)
        }
      }

      noMatches -> {
        item("no-matches", span = { GridItemSpan(maxLineSpan) }) {
          NoMatchesCard(query = searchQuery)
        }
      }

      else -> {
        if (searchActive && visibleDetections.isNotEmpty()) {
          item(key = "search-result", span = { GridItemSpan(maxLineSpan) }) {
            SearchResultsHeader(
              query = searchQuery,
              count = visibleDetections.size + visibleUncertainDetections.size,
            )
          }
        }
        if (!searchActive) {
          item(key = "status", span = { GridItemSpan(maxLineSpan) }) { ScanStatusCard(status) }
        }
        items(visibleDetections, key = { it.uri }) { item ->
          GalleryImageCard(
            item = item,
            onClick = remember(item.uri) { { onOpenImage(item.uri) } },
            animatedVisibilityScope = animatedVisibilityScope,
          )
        }

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
            span = { GridItemSpan(if (isTablet) maxLineSpan / 2 else maxLineSpan) },
          ) { image ->
            UncertainImageCard(
              modifier =
                Modifier.animateItem(
                  fadeInSpec = tween(200),
                  fadeOutSpec = tween(250),
                ),
              uri = image.uri,
              bestGuessScore = image.bestGuessScore,
              onClick = remember(image.uri) { { onOpenImage(image.uri) } },
              onAccept = remember { { uri: String, score: Int -> onAcceptUncertain(uri, score) } },
              onDelete = remember { { uri: String -> onDeleteUncertain(uri) } },
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
        text =
          "Try a different word or lower the similarity threshold in Settings → Developer Mode.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.GalleryImageCard(
  item: GalleryImageItem,
  onClick: () -> Unit,
  animatedVisibilityScope: AnimatedVisibilityScope,
) {
  val sharedState = rememberSharedContentState(key = "image_${item.uri}")
  Box(
    modifier =
      Modifier.fillMaxWidth()
        .aspectRatio(1f)
        .clip(RoundedCornerShape(24.dp))
        .clickable(onClick = onClick)
  ) {
    AsyncImage(
      model = item.uri,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier =
        Modifier.fillMaxSize()
          .sharedElement(sharedState, animatedVisibilityScope)
          .clip(RoundedCornerShape(24.dp)),
    )
    RatingStarsOverlay(
      scores = item.scores,
      modifier = Modifier.align(Alignment.TopStart),
    )
  }
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
        modifier =
          Modifier.weight(1f)
            .aspectRatio(1f)
            .sharedElement(sharedState, animatedVisibilityScope)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
      )
      Column(
        modifier = Modifier.weight(1.4f).padding(horizontal = 12.dp, vertical = 10.dp),
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
private fun UncertainStarSelector(selected: Int, onSelect: (Int) -> Unit) {
  Row {
    (1..5).forEach { score ->
      Box(
        modifier = Modifier.size(32.dp).clip(CircleShape).clickable { onSelect(score) },
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Filled.Star,
          contentDescription = "Rate $score",
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
 * Builds the scrollbar sections for the plain gallery: one section per group (month+year for date
 * sorting, top score for score sorting) in first-occurrence order along the grid, plus a single
 * uncertain section when best-guess images are shown. The scan-status card (grid item 0) is never a
 * section, so scan status never appears in any scrollbar pill.
 */
fun buildIndexSections(
  detections: List<GalleryImageItem>,
  uncertainDetections: List<SelectUncertainImagesWithScore>,
  sorting: ImageSorting,
): List<IndexScrollSection> {
  val sections = mutableListOf<IndexScrollSection>()
  val seen = mutableSetOf<Int>()
  val calendar = Calendar.getInstance()
  detections.forEachIndexed { index, item ->
    val key: Int =
      when (sorting) {
        is ImageSorting.Score -> item.scores.maxOfOrNull { it.score } ?: return@forEachIndexed
        is ImageSorting.Date ->
          item.scannedAt?.let { yearMonthKey(it, calendar) } ?: return@forEachIndexed
      }
    if (seen.add(key)) {
      sections += IndexScrollSection(startIndex = DETECTION_START_INDEX + index)
    }
  }
  if (uncertainDetections.isNotEmpty()) {
    sections +=
      IndexScrollSection(
        startIndex = DETECTION_START_INDEX + detections.size,
        isUncertain = true,
      )
  }
  return sections
}

/**
 * Label of the group under grid item [index] for the scrollbar chip: the month+year (date sorting)
 * or top rating (score sorting) of the item, or the best guess of the uncertain item when the index
 * is inside the uncertain section. Indices before the first photo (the scan-status card) and beyond
 * the last item clamp to the nearest group.
 */
fun indexLabel(
  index: Int,
  detections: List<GalleryImageItem>,
  uncertainDetections: List<SelectUncertainImagesWithScore>,
  sorting: ImageSorting,
): String {
  val uncertainStart = DETECTION_START_INDEX + detections.size
  val inUncertain = uncertainDetections.isNotEmpty() && index >= uncertainStart
  return if (inUncertain) {
    val item = uncertainDetections.getOrNull((index - uncertainStart - 1).coerceAtLeast(0))
    when (sorting) {
      is ImageSorting.Score -> item?.bestGuessScore?.score?.let { "$it★" } ?: "?"
      is ImageSorting.Date -> item?.scannedAt?.let { formatMonthYear(it) } ?: "?"
    }
  } else {
    val clamped =
      (index - DETECTION_START_INDEX).coerceIn(0, (detections.size - 1).coerceAtLeast(0))
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
private fun formatMonthYear(epochMillis: Long): String =
  SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(epochMillis))
