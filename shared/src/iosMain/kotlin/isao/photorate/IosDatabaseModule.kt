package isao.photorate

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import isao.photorate.app.db.PhotoRateDb
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.scope.Scope

@Module
class IosDatabaseModule {

  @Single
  fun provideSqlDriver(scope: Scope): SqlDriver =
    NativeSqliteDriver(PhotoRateDb.Schema, "PhotoRateDb")
}
