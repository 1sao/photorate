package isao.photorate.photosComponent.gallery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class BootSystemGalleryVersionChecker :
    BroadcastReceiver(),
    KoinComponent {
    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val resetGalleryOnVersionChange: ResetGalleryOnVersionChangeUseCase = get()

        // Safe to launch in GlobalScope as no structured concurrency is required and expected here.
        GlobalScope.launch {
            resetGalleryOnVersionChange.invoke()
        }
    }
}
