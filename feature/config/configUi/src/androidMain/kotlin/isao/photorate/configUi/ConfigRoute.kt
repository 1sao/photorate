package isao.photorate.configUi

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object ConfigRoute : NavKey

/** Navigation intents emitted by the config screen, handled at the app level. */
sealed interface ConfigNavigationIntent {
  data object Back : ConfigNavigationIntent

  data object PurgeAndRescan : ConfigNavigationIntent
}
