package isao.photorate.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import isao.photorate.searchUi.SearchUiState

/**
 * The expanded full-screen search UI shown inside the gallery's
 * [androidx.compose.material3.ExpandedFullScreenContainedSearchBar] content slot: recent-search
 * suggestions (or as-you-type history matches) and, in developer mode, the similarity-threshold
 * slider. The query input field itself is owned by the search bar, not this content.
 */
@Composable
fun ColumnScope.SearchContent(
  state: SearchUiState,
  onSelectRecent: (String) -> Unit,
  onMinSimilarityChange: (Float) -> Unit,
) {
  if (state.query.isBlank()) {
    RecentSearches(
      recentSearches = state.recentSearches,
      onSelectRecent = onSelectRecent,
      modifier = Modifier.weight(1f),
    )
  } else {
    SuggestionMatches(
      recentSearches = state.recentSearches,
      query = state.query,
      onSelectRecent = onSelectRecent,
      modifier = Modifier.weight(1f),
    )
  }
  if (state.devModeEnabled) {
    SimilarityThresholdSlider(
      value = state.minSimilarity,
      onValueChange = onMinSimilarityChange,
    )
  }
}

/**
 * Large recent-search suggestions filling the remaining space, shown while the query is empty
 * (Material search guidelines: suggestions take the whole screen).
 */
@Composable
private fun RecentSearches(
  recentSearches: List<String>,
  onSelectRecent: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (recentSearches.isEmpty()) {
    Box(
      modifier = modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = "Type a query to search your photos",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    return
  }
  Column(modifier) {
    Text(
      text = "Recent searches",
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
    LazyColumn(
      modifier = Modifier.fillMaxWidth().weight(1f),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      items(recentSearches, key = { it }) { recent ->
        SuggestionRow(text = recent, onClick = { onSelectRecent(recent) })
      }
    }
  }
}

/** History suggestions matching the typed prefix (as-you-type suggestions). */
@Composable
private fun SuggestionMatches(
  recentSearches: List<String>,
  query: String,
  onSelectRecent: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val matches = recentSearches.filter { it.contains(query, ignoreCase = true) }
  LazyColumn(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    items(matches, key = { it }) { match ->
      SuggestionRow(text = match, onClick = { onSelectRecent(match) })
    }
  }
}

@Composable
private fun SuggestionRow(text: String, onClick: () -> Unit) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 20.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      Icons.Filled.Search,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.width(16.dp))
    Text(
      text = text,
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.weight(1f),
      maxLines = 1,
    )
  }
}

/**
 * Developer-mode control: sets the CLIP-similarity cutoff for search results. Re-runs the active
 * query on every change so the threshold's effect is immediately visible on the gallery grid.
 * Uncertain (low-confidence) images are part of the search pool, so they show up here too.
 */
@Composable
private fun SimilarityThresholdSlider(value: Float, onValueChange: (Float) -> Unit) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Match certainty",
          style = MaterialTheme.typography.titleSmall,
          modifier = Modifier.weight(1f),
        )
        Text(
          text = "%.2f".format(value),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
      }
      Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = 0f..0.6f,
      )
      Text(
        text = "Hide matches below this similarity (dev mode).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
