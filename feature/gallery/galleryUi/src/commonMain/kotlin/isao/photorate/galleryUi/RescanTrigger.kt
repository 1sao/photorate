package isao.photorate.galleryUi

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.koin.core.annotation.Single

/**
 * App-wide rescan request bus. Screens outside the gallery (e.g. Settings, after purging all stored
 * data) emit a request; HomeUi's HomeViewModel collects it and re-runs the full scan.
 */
@Single
class RescanTrigger {
  sealed interface Request {
    data object Rescan : Request

    data object PurgeAndRescan : Request
  }

  private val _requests = MutableSharedFlow<Request>(extraBufferCapacity = 1)
  val requests: SharedFlow<Request> = _requests.asSharedFlow()

  fun request() {
    _requests.tryEmit(Request.Rescan)
  }

  fun requestPurgeAndRescan() {
    _requests.tryEmit(Request.PurgeAndRescan)
  }
}
