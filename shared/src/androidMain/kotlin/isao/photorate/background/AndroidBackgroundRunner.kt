package isao.photorate.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.android.annotation.KoinWorker

class AndroidBackgroundRunner : BackgroundRunner {
    init {
    }
}

@KoinWorker
class MyWorker(
    context: Context,
    workerParams: WorkerParameters,
    // private val myService: MyService
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        // myService.performTask()
        return Result.success()
    }
}
