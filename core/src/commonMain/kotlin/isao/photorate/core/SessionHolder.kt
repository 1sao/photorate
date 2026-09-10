package isao.photorate.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

/**
 * Manages an [AutoCloseable]'s lifecycle. The [AutoCloseable] is created on demand, is shared
 * between concurrent uses, and is closed if not in use for [closeAfterUnused].
 */
abstract class SessionHolder<Session : AutoCloseable>(
  private val factory: () -> Session,
  closeAfterUnused: Duration = 5.seconds,
) {
  @OptIn(DelicateCoroutinesApi::class)
  private val sessionFlow: SharedFlow<Session> = flow {
    val session = factory()
    try {
      emit(session)
      awaitCancellation()
    } finally {
      session.close()
    }
  }
    .shareIn(
      scope = GlobalScope,
      started =
        SharingStarted.WhileSubscribed(
          stopTimeout = closeAfterUnused,
          replayExpiration = 0.seconds,
        ),
      replay = 1,
    )

  // TODO decide whether to keep this
  private suspend fun <T> useAlternative(block: suspend (Session) -> T): T = coroutineScope {
    val reservation = stayAliveWhenScopeActive(this)
    try {
      block(sessionFlow.first())
    } finally {
      reservation.cancel()
    }
  }

  suspend fun <T> use(block: suspend (Session) -> T): T {
    var result: T? = null
    sessionFlow.take(1).map { block(it) }.collect { result = it }
    return result!!
  }

  /** Keeps the session alive while the [scope] is alive. */
  fun stayAliveWhenScopeActive(scope: CoroutineScope): Job {
    return scope.launch { sessionFlow.collect() }
  }
}

/** Keeps the session alive while the [Flow] is being collected. */
fun <T> Flow<T>.keepAlive(holder: SessionHolder<*>): Flow<T> {
  val upstream = this
  return channelFlow {
    holder.stayAliveWhenScopeActive(this)
    upstream.collect { send(it) }
  }
}
