package isao.photorate.homeUi

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AppBarWithSearchColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchInputField(
  searchBarState: SearchBarState,
  textFieldState: TextFieldState,
  colors: AppBarWithSearchColors,
  onClose: () -> Unit,
  onIntent: (HomeIntent) -> Unit,
) {
  SearchBarDefaults.InputField(
    textFieldState = textFieldState,
    searchBarState = searchBarState,
    colors = colors.searchBarColors.inputFieldColors,
    onSearch = { text ->
      onIntent(HomeIntent.UpdateSearchQuery(text))
      onIntent(HomeIntent.SubmitSearch)
      onClose()
    },
    placeholder = { Text("Search your photos…") },
    leadingIcon = {
      if (searchBarState.currentValue == SearchBarValue.Expanded) {
        IconButton(onClick = onClose) {
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
        IconButton(onClick = { onIntent(HomeIntent.ClearSearch) }) {
          Icon(Icons.Filled.Close, contentDescription = "Clear search")
        }
      }
    },
  )
}
