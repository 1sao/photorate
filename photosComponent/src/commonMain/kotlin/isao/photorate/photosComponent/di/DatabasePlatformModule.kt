package isao.photorate.photosComponent.di

import app.cash.sqldelight.db.SqlDriver
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.scope.Scope

/**
 * Platform-specific SQLite driver provider. Each platform source set provides
 * an [actual] implementation ([DatabasePlatformModule.android.kt],
 * [DatabasePlatformModule.ios.kt]).
 */
@Module
expect class DatabasePlatformModule {
    @Single
    fun provideSqlDriver(scope: Scope): SqlDriver
}
