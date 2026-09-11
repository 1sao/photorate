package isao.photorate.homeUi

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import isao.photorate.searchUi.SearchIntent
import isao.photorate.searchUi.SearchIntent.SubmitSearch
import isao.photorate.searchUi.SearchIntent.UpdateSearchQuery

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchInputField(
  searchBarState: SearchBarState,
  textFieldState: TextFieldState,
  colors: TextFieldColors,
  onClose: () -> Unit,
  onIntent: (HomeIntent) -> Unit,
  modifier: Modifier = Modifier,
) {
  SearchBarDefaults.InputField(
    textFieldState = textFieldState,
    searchBarState = searchBarState,
    modifier = modifier,
    colors = colors,
    onSearch = { text ->
      onIntent(HomeIntent.Search(UpdateSearchQuery(text)))
      onIntent(HomeIntent.Search(SubmitSearch))
      onClose()
    },
    placeholder = { Text("Search your photos…") },
    leadingIcon = {
      if (searchBarState.currentValue == SearchBarValue.Expanded) {
        IconButton(
          onClick = {
            onIntent(HomeIntent.Search(SearchIntent.ClearSearch))
            onClose()
          },
        ) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
      } else {
        Icon(
          Icons.Filled.Search,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    trailingIcon = {
      if (textFieldState.text.isNotEmpty()) {
        IconButton(onClick = { onIntent(HomeIntent.Search(SearchIntent.ClearSearch)) }) {
          Icon(Icons.Filled.Close, contentDescription = "Clear search")
        }
      }
    },
  )
}
