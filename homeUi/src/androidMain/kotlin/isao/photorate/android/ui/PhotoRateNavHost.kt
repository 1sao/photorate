package isao.photorate.android.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import isao.photorate.configUi.ConfigViewModel
import isao.photorate.galleryUi.ImageDetailsIntent
import isao.photorate.galleryUi.ImageDetailsViewModel
import isao.photorate.homeUi.HomeViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
  @Serializable data object Home : Route

  @Serializable data class ImageDetails(val uri: String) : Route

  @Serializable data object Config : Route
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PhotoRateNavHost(
  homeViewModel: HomeViewModel,
  imageDetailsViewModel: ImageDetailsViewModel,
  configViewModel: ConfigViewModel,
  onOpenAppSettings: () -> Unit,
  appVersionName: String,
  appVersionCode: Int,
) {
  val backStack = rememberNavBackStack(Route.Home)
  val scope = rememberCoroutineScope()

  SharedTransitionLayout {
    NavDisplay(
      backStack = backStack,
      modifier = Modifier.fillMaxSize(),
      sharedTransitionScope = this,
      entryProvider = { key: NavKey ->
        val route = key as Route
        NavEntry(key) {
          when (route) {
            is Route.Home ->
              HomeScreen(
                homeViewModel = homeViewModel,
                animatedVisibilityScope = LocalNavAnimatedContentScope.current,
                onOpenSettings = { backStack += Route.Config },
                onOpenAppSettings = onOpenAppSettings,
                onOpenImage = { uri ->
                  imageDetailsViewModel.onIntent(ImageDetailsIntent.Load(uri))
                  backStack += Route.ImageDetails(uri)
                },
                onIntent = homeViewModel::onIntent,
              )

            is Route.ImageDetails ->
              ImageDetailsScreen(
                uri = route.uri,
                imageDetailsViewModel = imageDetailsViewModel,
                onBack = { backStack.removeLastOrNull() },
                animatedVisibilityScope = LocalNavAnimatedContentScope.current,
              )

            is Route.Config ->
              ConfigScreen(
                configViewModel = configViewModel,
                onBack = { backStack.removeLastOrNull() },
                onPurgeAndRescan = { scope.launch { homeViewModel.purgeAndRescan() } },
                appVersionName = appVersionName,
                appVersionCode = appVersionCode,
              )
          }
        }
      },
    )
  }
}
