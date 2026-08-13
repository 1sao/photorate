package isao.photorate.android.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.example.coreui.composable.LocalSharedTransitionScope
import isao.photorate.configUi.ConfigViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

@Serializable
sealed interface Route : NavKey {
  @Serializable data object Home : Route

  @Serializable data class ImageDetails(val uri: String) : Route

  @Serializable data object Config : Route
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PhotoRateNavHost(
  configViewModel: ConfigViewModel,
  onOpenAppSettings: () -> Unit,
  appVersionName: String, // TODO inject as a data class in the place where it is used
  appVersionCode: Int, // TODO inject as a data class in the place where it is used
) {
  val backStack =
    rememberSerializable(serializer = serializer()) { NavBackStack<Route>(Route.Home) }
  val scope = rememberCoroutineScope()

  SharedTransitionLayout {
    NavDisplay(
      backStack = backStack,
      modifier = Modifier.fillMaxSize(),
      sharedTransitionScope = this,
      entryDecorators =
        listOf(
          rememberSaveableStateHolderNavEntryDecorator(),
          rememberViewModelStoreNavEntryDecorator(),
          rememberLocalSharedTransitionScopeDecorator(this),
        ),
      entryProvider = { route: Route ->
        NavEntry(route) {
          when (route) {
            is Route.Home ->
              HomeScreen(
                onOpenSettings = { backStack += Route.Config },
                onOpenAppSettings = onOpenAppSettings,
                onOpenImage = { uri -> backStack += Route.ImageDetails(uri) },
              )

            is Route.ImageDetails ->
              ImageDetailsScreen(
                uri = route.uri,
                onBack = { backStack.removeLastOrNull() },
              )

            is Route.Config ->
              ConfigScreen(
                configViewModel = configViewModel,
                onBack = { backStack.removeLastOrNull() },
                onPurgeAndRescan = {
                  scope.launch {
                    // TODO rework
                    //                  homeViewModel.purgeAndRescan()
                  }
                },
                appVersionName = appVersionName,
                appVersionCode = appVersionCode,
              )
          }
        }
      },
    )
  }
}

@Composable
private fun rememberLocalSharedTransitionScopeDecorator(
  scope: SharedTransitionScope
): LocalSharedTransitionScopeDecorator =
  remember(scope) { LocalSharedTransitionScopeDecorator(scope) }

private class LocalSharedTransitionScopeDecorator(scope: SharedTransitionScope) :
  NavEntryDecorator<Route>(
    decorate = { entry ->
      CompositionLocalProvider(LocalSharedTransitionScope provides scope) { entry.Content() }
    },
  )
