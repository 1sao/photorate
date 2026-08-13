package isao.photorate

import isao.photorate.configUi.ConfigNavigationIntent
import isao.photorate.configUi.ConfigRoute
import isao.photorate.coreUi.navigation.IntentHandler
import isao.photorate.galleryUi.GalleryNavigationIntent
import isao.photorate.galleryUi.ImageDetailsRoute
import isao.photorate.galleryUi.RescanTrigger
import isao.photorate.homeUi.HomeNavigationIntent
import isao.photorate.homeUi.NavDispatcher
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.scope.Scope

/** App-level navigation: maps each module's navigation intents to back-stack commands. */
@Module
class AppNavigationModule {

  @Single
  fun provideHomeIntentHandler(scope: Scope): IntentHandler<HomeNavigationIntent> {
    val dispatcher = scope.get<NavDispatcher>()
    return IntentHandler { intent ->
      when (intent) {
        HomeNavigationIntent.OpenSettings ->
          dispatcher.dispatch { backStack -> backStack.add(ConfigRoute) }

        is HomeNavigationIntent.OpenImage ->
          dispatcher.dispatch { backStack -> backStack.add(ImageDetailsRoute(intent.uri)) }
      }
    }
  }

  @Single
  fun provideGalleryIntentHandler(scope: Scope): IntentHandler<GalleryNavigationIntent> {
    val dispatcher = scope.get<NavDispatcher>()
    return IntentHandler { intent ->
      when (intent) {
        GalleryNavigationIntent.Back ->
          dispatcher.dispatch { backStack -> backStack.removeLastOrNull() }
      }
    }
  }

  @Single
  fun provideConfigIntentHandler(scope: Scope): IntentHandler<ConfigNavigationIntent> {
    val dispatcher = scope.get<NavDispatcher>()
    val rescanTrigger = scope.get<RescanTrigger>()
    return IntentHandler { intent ->
      when (intent) {
        ConfigNavigationIntent.Back ->
          dispatcher.dispatch { backStack -> backStack.removeLastOrNull() }

        ConfigNavigationIntent.PurgeAndRescan -> rescanTrigger.requestPurgeAndRescan()
      }
    }
  }
}
