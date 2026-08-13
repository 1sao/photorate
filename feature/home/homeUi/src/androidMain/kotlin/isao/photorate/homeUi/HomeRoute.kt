package isao.photorate.homeUi

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute : NavKey

/** Navigation intents emitted by the home screen, handled at the app level. */
sealed interface HomeNavigationIntent {
  data object OpenSettings : HomeNavigationIntent

  data class OpenImage(val uri: String) : HomeNavigationIntent
}
