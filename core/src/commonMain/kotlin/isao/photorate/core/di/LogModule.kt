package isao.photorate.core.di

import co.touchlab.kermit.Logger
import co.touchlab.kermit.StaticConfig
import co.touchlab.kermit.platformLogWriter
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

/**
 * Kermit logger provider.
 *
 * Lives in this leaf module because components across every feature module inject [Logger]. The
 * Koin compiler plugin verifies each `@Module` class against its own Gradle module's definitions
 * (KOIN-D001), so a binding used by a leaf module must be defined in that module (or one it depends
 * on) — never in the consumer. The authoritative full-graph check still runs at the root
 * `@KoinApplication` in `shared`.
 */
@Module
class LogModule {
  @Single
  fun provideLogger(): Logger =
    Logger(
      config = StaticConfig(logWriterList = listOf(platformLogWriter())),
      tag = "PhotoRate",
    )
}
