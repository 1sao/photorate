package isao.photorate.galleryUi

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data class ImageDetailsRoute(val uri: String) : NavKey

/** Navigation intents emitted by the image details screen, handled at the app level. */
sealed interface GalleryNavigationIntent {
  data object Back : GalleryNavigationIntent
}
