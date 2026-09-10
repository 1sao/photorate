package isao.photorate.homeUi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import isao.photorate.core.keepAlive
import isao.photorate.galleryComponent.populateGallery.GallerySyncManager
import isao.photorate.galleryUi.GalleryDelegate
import isao.photorate.galleryUi.GalleryIntent
import isao.photorate.galleryUi.GalleryUiState
import isao.photorate.searchComponent.SearchSessionHolder
import isao.photorate.searchUi.SearchDelegate
import isao.photorate.searchUi.SearchIntent
import isao.photorate.searchUi.SearchUiState
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

/** Manages gallery grid and search, provided by [GalleryDelegate] and [SearchDelegate] */
@KoinViewModel
class HomeViewModel(
  private val galleryDelegate: GalleryDelegate,
  private val searchDelegate: SearchDelegate,
  private val gallerySyncManager: GallerySyncManager,
  searchSessionHolder: SearchSessionHolder,
) : ViewModel() {
  val uiState: StateFlow<HomeScreenUiState> =
    combine(
        galleryDelegate.uiState,
        searchDelegate.uiState,
      ) { gallery, search ->
        HomeScreenUiState(
          gallery,
          search,
        )
      }
      .keepAlive(searchSessionHolder)
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5.seconds),
        initialValue = HomeScreenUiState(),
      )

  fun onIntent(intent: HomeIntent) {
    viewModelScope.launch {
      when (intent) {
        HomeIntent.GalleryPermissionGranted -> gallerySyncManager.run()
        is HomeIntent.Gallery -> galleryDelegate.onIntent(intent.intent)
        is HomeIntent.Search -> searchDelegate.onIntent(intent.intent)
      }
    }
  }
}

data class HomeScreenUiState(
  val gallery: GalleryUiState = GalleryUiState(),
  val search: SearchUiState = SearchUiState(),
)

sealed interface HomeIntent {
  data object GalleryPermissionGranted : HomeIntent

  data class Gallery(val intent: GalleryIntent) : HomeIntent

  data class Search(val intent: SearchIntent) : HomeIntent
}
