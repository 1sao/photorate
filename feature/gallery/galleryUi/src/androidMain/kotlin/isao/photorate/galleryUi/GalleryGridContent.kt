package isao.photorate.galleryUi

import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import coil3.request.crossfade
import isao.photorate.coreUi.composable.LocalSharedTransitionScope
import isao.photorate.coreUi.composable.PhotoRatePreview
import isao.photorate.coreUi.composable.imageSharedContentKey
import isao.photorate.coreUi.composable.rememberImageUnavailablePainter
import isao.photorate.imageRecognition.Score

// TODO verify if the search is slow enough to require this
@Composable
private fun SearchingIndicator(query: String, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier.fillMaxWidth().padding(vertical = 24.dp),
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
private fun SearchResultsHeader(query: String, count: Int, modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier.fillMaxWidth(),
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
fun NoMatchesCard(query: String, modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier.fillMaxWidth(),
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
          "Try to word your search differently, or tweak the similarity threshold in Settings → Developer Mode.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
fun GalleryItem(
  item: GalleryImageItem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier.fillMaxWidth().aspectRatio(1f).clickable(onClick = onClick)) {
    GalleryCardImage(
      uri = item.uri,
      modifier = Modifier.fillMaxSize(),
    )
    RatingStar(
      fraction = item.score.ratingStarFraction,
      modifier = Modifier.padding(12.dp).size(20.dp),
    )
  }
}

@Composable
fun UncertainGalleryItem(
  item: GalleryImageItem,
  onClick: () -> Unit,
  onAccept: (String, Int) -> Unit,
  onDelete: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.largeIncreased,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
      GalleryCardImage(
        uri = item.uri,
        onClick = onClick,
        modifier = Modifier.weight(1f).aspectRatio(1f),
      )

      var stars by rememberSaveable { mutableIntStateOf(item.score.score) }
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
      .crossfade(true)
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
    error = rememberImageUnavailablePainter(),
    contentScale = ContentScale.Crop,
    modifier =
      modifier.then(sharedBounds).clip(clipShape).let {
        if (onClick != null) it.clickable(onClick = onClick) else it
      },
  )
}

@Composable
private fun UncertainSectionHeader(count: Int, modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier.fillMaxWidth(),
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

@Preview
@Composable
private fun GalleryItemPreview() {
  PhotoRatePreview {
    GalleryItem(
      item = GalleryImageItem("content://preview/a", Score.FIVE),
      onClick = {},
    )
  }
}

@Preview
@Composable
private fun UncertainGalleryItemPreview() {
  PhotoRatePreview {
    UncertainGalleryItem(
      item = GalleryImageItem("content://preview/u1", Score.THREE),
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
