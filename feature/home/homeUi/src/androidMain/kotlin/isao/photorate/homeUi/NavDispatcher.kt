package isao.photorate.homeUi

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * App-wide navigation command bus. The NavHost applies each command to the composition-owned,
 * process-death-safe back stack; Koin-held objects never reference the stack themselves, so no
 * composition state is retained outside the composition.
 */
class NavDispatcher {
  private val _commands = MutableSharedFlow<(NavBackStack<NavKey>) -> Unit>(extraBufferCapacity = 1)
  val commands: SharedFlow<(NavBackStack<NavKey>) -> Unit> = _commands.asSharedFlow()

  fun dispatch(command: (NavBackStack<NavKey>) -> Unit) {
    _commands.tryEmit(command)
  }
}
