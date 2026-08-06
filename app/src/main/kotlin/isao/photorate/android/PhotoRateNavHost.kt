package isao.photorate.android

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import isao.photorate.android.ui.ConfigScreen
import isao.photorate.android.ui.ImageDetailsScreen
import isao.photorate.android.ui.ThumbRatingScreen
import isao.photorate.configUi.ConfigViewModel
import isao.photorate.galleryUi.GalleryViewModel
import isao.photorate.galleryUi.ImageDetailsIntent
import isao.photorate.galleryUi.ImageDetailsViewModel
import kotlinx.serialization.Serializable

/**
 * The app's type-safe destinations. The back stack only ever holds [Route]
 * instances, so the entry provider can cast the generic [NavKey] safely.
 */
@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Gallery : Route

    @Serializable
    data class ImageDetails(val uri: String) : Route

    @Serializable
    data object Config : Route
}

/**
 * Navigation3 host for the whole app: the gallery grid is the start
 * destination; the details and settings screens are pushed on top and popped
 * with system back or their top-bar buttons. Wrapped in a [SharedTransitionLayout]
 * so the grid cards and the details photo share the "image_<uri>" element.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PhotoRateNavHost(
    galleryViewModel: GalleryViewModel,
    imageDetailsViewModel: ImageDetailsViewModel,
    configViewModel: ConfigViewModel,
    onOpenAppSettings: () -> Unit,
    appVersionName: String,
    appVersionCode: Int,
) {
    val backStack = rememberNavBackStack(Route.Gallery)

    SharedTransitionLayout {
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.fillMaxSize(),
            sharedTransitionScope = this,
            entryProvider = { key: NavKey ->
                val route = key as Route
                NavEntry(key) {
                    when (route) {
                        is Route.Gallery -> ThumbRatingScreen(
                            galleryViewModel = galleryViewModel,
                            animatedVisibilityScope = LocalNavAnimatedContentScope.current,
                            onOpenSettings = { backStack += Route.Config },
                            onOpenAppSettings = onOpenAppSettings,
                            // Load eagerly so the details screen has data when
                            // the shared-element transition lands; the route
                            // content's LaunchedEffect re-loads on recreation.
                            onOpenImage = { uri ->
                                imageDetailsViewModel.onIntent(ImageDetailsIntent.Load(uri))
                                backStack += Route.ImageDetails(uri)
                            },
                            onIntent = galleryViewModel::onIntent,
                        )

                        is Route.ImageDetails -> ImageDetailsScreen(
                            uri = route.uri,
                            imageDetailsViewModel = imageDetailsViewModel,
                            onBack = { backStack.removeLastOrNull() },
                            animatedVisibilityScope = LocalNavAnimatedContentScope.current,
                        )

                        is Route.Config -> ConfigScreen(
                            configViewModel = configViewModel,
                            onBack = { backStack.removeLastOrNull() },
                            appVersionName = appVersionName,
                            appVersionCode = appVersionCode,
                        )
                    }
                }
            },
        )
    }
}
