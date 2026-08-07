package isao.photorate

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import isao.photorate.search.db.PhotoRateDb
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.scope.Scope

/**
 * The single [SqlDriver] singleton on iOS, created with the most complete schema visible from
 * `shared` (searchComponent's generated database merges the config + gallery + search tables). The
 * feature modules' `@Provided` driver parameters resolve to this binding.
 */
@Module
class IosDatabaseModule {

  @Single
  fun provideSqlDriver(scope: Scope): SqlDriver =
    NativeSqliteDriver(PhotoRateDb.Schema, "PhotoRateDb")
}
