package isao.photorate.core.di

import co.touchlab.kermit.Logger
import co.touchlab.kermit.StaticConfig
import co.touchlab.kermit.platformLogWriter
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module
class LogModule {
  @Single
  fun provideLogger(): Logger =
    Logger(
      // TODO automatically track logged errors with crashlytics
      config = StaticConfig(logWriterList = listOf(platformLogWriter())),
      tag = "PhotoRate",
    )
}
