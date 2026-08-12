package isao.photorate.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.ExpandedFullScreenContainedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarScrollBehavior
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.rememberContainedSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.IntOffset
import com.example.coreui.composable.rememberSyncedTextFieldState
import isao.photorate.homeUi.HomeIntent
import isao.photorate.homeUi.HomeScreenUiState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TopBar(
  state: HomeScreenUiState,
  scrollBehavior: SearchBarScrollBehavior,
  onOpenSettings: () -> Unit,
  onIntent: (HomeIntent) -> Unit,
) {
  val scope = rememberCoroutineScope()

  val searchBarState = rememberContainedSearchBarState()

  val appBarWithSearchColors =
    SearchBarDefaults.appBarWithSearchColors(
      searchBarColors = SearchBarDefaults.containedColors(state = searchBarState),
    )

  val inputField =
    @Composable {
      SearchInputField(
        searchBarState = searchBarState,
        textFieldState =
          rememberSyncedTextFieldState(
            state.search.query,
            onChange = { onIntent(HomeIntent.UpdateSearchQuery(it)) },
          ),
        colors = appBarWithSearchColors,
        onClose = { scope.launch { searchBarState.animateToCollapsed() } },
        onIntent = onIntent,
      )
    }

  AppBarWithSearch(
    state = searchBarState,
    scrollBehavior = scrollBehavior,
    colors = appBarWithSearchColors,
    inputField = inputField,
    actions = {
      AnimatedVisibility(
        visible = searchBarState.targetValue == SearchBarValue.Collapsed,
        enter =
          slideIn(
            animationSpec = tween(durationMillis = 150),
            initialOffset = { IntOffset(it.width, 0) },
          ),
        exit =
          slideOut(
            animationSpec = tween(durationMillis = 150),
            targetOffset = { IntOffset(it.width, 0) },
          ),
      ) {
        IconButton(onClick = onOpenSettings) {
          Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }
      }
    },
  )
  ExpandedFullScreenContainedSearchBar(
    state = searchBarState,
    inputField = inputField,
    colors = appBarWithSearchColors.searchBarColors,
  ) {
    SearchContent(
      state = state.search,
      onSelectRecent = { query ->
        onIntent(HomeIntent.SelectRecentSearch(query))
        scope.launch { searchBarState.animateToCollapsed() }
      },
      onMinSimilarityChange = { value -> onIntent(HomeIntent.SetMinSimilarity(value)) },
    )
  }
}
