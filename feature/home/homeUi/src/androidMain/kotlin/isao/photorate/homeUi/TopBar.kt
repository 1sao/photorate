package isao.photorate.homeUi

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.AppBarWithSearchColors
import androidx.compose.material3.ExpandedFullScreenContainedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarScrollBehavior
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberContainedSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import isao.photorate.coreUi.composable.rememberSyncedTextFieldState
import isao.photorate.searchUi.SearchContent
import isao.photorate.searchUi.SearchIntent
import isao.photorate.searchUi.SearchIntent.SelectRecentSearch
import isao.photorate.searchUi.SearchIntent.UpdateSearchQuery
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TopBar(
  state: HomeScreenUiState,
  scrollBehavior: SearchBarScrollBehavior,
  onOpenSettings: () -> Unit,
  onIntent: (HomeIntent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()

  val searchBarState = rememberContainedSearchBarState()

  val defaultAppBarWithSearchColors = SearchBarDefaults.appBarWithSearchColors()
  val appBarWithSearchColors =
    SearchBarDefaults.appBarWithSearchColors(
      searchBarColors = SearchBarDefaults.containedColors(state = searchBarState),
      scrolledAppBarContainerColor =
        defaultAppBarWithSearchColors.scrolledAppBarContainerColor.copy(alpha = 0f),
    )

  val inputField =
    @Composable {
      SearchInputField(
        searchBarState = searchBarState,
        textFieldState =
          rememberSyncedTextFieldState(
            state.search.pendingQuery.value,
            onChange = { onIntent(HomeIntent.Search(UpdateSearchQuery(it))) },
          ),
        colors =
          appBarWithSearchColors.searchBarColors.inputFieldColors.withHiddenCursorDuringAnimation(
            searchBarState,
          ),
        onClose = { scope.launch { searchBarState.animateToCollapsed() } },
        onIntent = onIntent,
      )
    }

  AppBarWithSearch(
    state = searchBarState,
    modifier = modifier,
    scrollBehavior = scrollBehavior,
    colors = appBarWithSearchColors,
    inputField = inputField,
    actions = {
      SettingsButton(
        onClick = onOpenSettings,
        isSearchCollapsed = searchBarState.targetValue == SearchBarValue.Collapsed,
        appBarWithSearchColors = appBarWithSearchColors,
      )
    },
    // End is bigger to account for the larger than normal settings button.
    contentPadding = PaddingValues(start = 4.dp, end = 8.dp),
  )
  // TODO use another type for tablets
  ExpandedFullScreenContainedSearchBar(
    state = searchBarState,
    inputField = inputField,
    colors = appBarWithSearchColors.searchBarColors,
  ) {
    SearchContent(
      state = state.search,
      onSelectRecent = { query ->
        onIntent(HomeIntent.Search(SelectRecentSearch(query)))
        scope.launch { searchBarState.animateToCollapsed() }
      },
      onMinSimilarityChange = { value ->
        onIntent(HomeIntent.Search(SearchIntent.SetMinSimilarity(value)))
      },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsButton(
  onClick: () -> Unit,
  isSearchCollapsed: Boolean,
  appBarWithSearchColors: AppBarWithSearchColors,
  modifier: Modifier = Modifier,
) {
  AnimatedVisibility(
    visible = isSearchCollapsed,
    modifier = modifier,
    enter =
      slideIn(
        animationSpec = spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMedium),
        initialOffset = { IntOffset(it.width, 0) },
      ),
    exit =
      slideOut(
        animationSpec = spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMedium),
        targetOffset = { IntOffset(it.width, 0) },
      ),
  ) {
    FilledIconButton(
      onClick = onClick,
      modifier = Modifier.requiredSize(56.dp),
      colors =
        IconButtonDefaults.filledIconButtonColors(
          containerColor = appBarWithSearchColors.searchBarColors.containerColor,
        ),
    ) {
      Icon(Icons.Filled.Settings, contentDescription = "Settings")
    }
  }
}

/**
 * Hides the cursor during expand/shrink animations to avoid flickering. May be useless in future if
 * it's fixed in Material3.
 */
@Composable
private fun TextFieldColors.withHiddenCursorDuringAnimation(
  searchBarState: SearchBarState
): TextFieldColors {
  if (!searchBarState.isAnimating) return this
  return copy(cursorColor = Color.Transparent)
}
