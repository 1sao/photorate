package isao.photorate.android

import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import isao.photorate.app.db.PhotoRateDb
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.scope.Scope

/**
 * The single [SqlDriver] singleton for the whole app, created with the FULL merged schema
 * (`app.db.PhotoRateDb.Schema` merges every feature module's tables). Feature modules construct
 * their own generated DB classes over this driver. Registered from [MainApp] via `initKoin {
 * modules(...) }` — the feature modules' `@Provided` driver parameters resolve to this binding.
 */
@Module
class AppDatabaseModule {

  @Single
  fun provideSqlDriver(scope: Scope): SqlDriver =
    AndroidSqliteDriver(
      PhotoRateDb.Schema,
      scope.get(),
      "PhotoRateDb",
      callback =
        object : AndroidSqliteDriver.Callback(PhotoRateDb.Schema) {
          override fun onOpen(db: SupportSQLiteDatabase) {
            // Enable foreign keys for SQLite
            db.execSQL("PRAGMA foreign_keys=ON;")
          }
        },
    )
}
