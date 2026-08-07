package isao.photorate.config

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import isao.photorate.config.db.Config
import isao.photorate.config.db.PhotoRateDb
import isao.photorate.core.CoreModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

// The SqlDriver is `@Provided` — bound at the root by `/app` (Android) or
// `shared` iosMain (iOS), which this leaf module cannot see.
@Module(includes = [CoreModule::class])
@ComponentScan("isao.photorate.config")
class ConfigModule {
  @Single
  fun provideConfigDb(@Provided driver: SqlDriver): PhotoRateDb =
    PhotoRateDb(
      driver,
      configAdapter =
        Config.Adapter(
          sort_byAdapter = EnumColumnAdapter(),
          date_header_modeAdapter = EnumColumnAdapter(),
        ),
    )
}
