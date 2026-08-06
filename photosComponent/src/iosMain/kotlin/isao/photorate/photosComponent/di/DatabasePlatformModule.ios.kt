package isao.photorate.photosComponent.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import isao.photorate.db.PhotoRateDb
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.scope.Scope

@Module
actual class DatabasePlatformModule {

    @Single
    actual fun provideSqlDriver(scope: Scope): SqlDriver = NativeSqliteDriver(PhotoRateDb.Schema, "PhotoRateDb")
}
