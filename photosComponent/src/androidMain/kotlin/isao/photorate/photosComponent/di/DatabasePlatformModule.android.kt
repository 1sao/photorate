package isao.photorate.photosComponent.di

import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import isao.photorate.db.PhotoRateDb
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.scope.Scope

@Module
actual class DatabasePlatformModule {

    @Single
    actual fun provideSqlDriver(scope: Scope): SqlDriver = AndroidSqliteDriver(
        PhotoRateDb.Schema,
        scope.get(),
        "PhotoRateDb",
        callback = object : AndroidSqliteDriver.Callback(PhotoRateDb.Schema) {
            override fun onOpen(db: SupportSQLiteDatabase) {
                // Enable foreign keys for SQLite
                db.execSQL("PRAGMA foreign_keys=ON;")
            }
        },
    )
}
