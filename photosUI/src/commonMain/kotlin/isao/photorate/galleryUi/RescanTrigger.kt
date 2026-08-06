package isao.photorate.galleryUi

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.koin.core.annotation.Single

/**
 * App-wide rescan request bus. Screens outside the gallery (e.g. Settings,
 * after purging all stored data) emit a request; [GalleryViewModel] collects
 * it and re-runs the full scan. Single-element buffer so a request is never
 * lost while the collector is between suspensions; the gallery VM's
 * single-flight guard drops requests that arrive mid-scan.
 */
@Single
class RescanTrigger {
    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requests: SharedFlow<Unit> = _requests.asSharedFlow()

    fun request() {
        _requests.tryEmit(Unit)
    }
}
