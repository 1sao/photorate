package isao.photorate.homeUi

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import isao.photorate.coreUi.composable.LocalSharedTransitionScope
import org.koin.compose.koinInject
import org.koin.compose.navigation3.koinEntryProvider
import org.koin.core.annotation.KoinExperimentalAPI

@OptIn(KoinExperimentalAPI::class, ExperimentalSharedTransitionApi::class)
@Composable
fun PhotoRateNavHost() {
  val backStack = rememberNavBackStack(HomeRoute)
  val dispatcher = koinInject<NavDispatcher>()
  // The collector restarts when a restored instance replaces the stack, so commands always target
  // the live, saveable back stack.
  LaunchedEffect(backStack) { dispatcher.commands.collect { command -> command(backStack) } }

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
      entryProvider = koinEntryProvider<NavKey>(),
    )
  }
}

@Composable
private fun rememberLocalSharedTransitionScopeDecorator(
  scope: SharedTransitionScope
): LocalSharedTransitionScopeDecorator =
  remember(scope) { LocalSharedTransitionScopeDecorator(scope) }

private class LocalSharedTransitionScopeDecorator(scope: SharedTransitionScope) :
  NavEntryDecorator<NavKey>(
    decorate = { entry ->
      CompositionLocalProvider(LocalSharedTransitionScope provides scope) { entry.Content() }
    },
  )
