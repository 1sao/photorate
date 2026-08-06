package isao.photorate.photosComponent.di

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import isao.photorate.db.Config
import isao.photorate.db.DetectedHand
import isao.photorate.db.GalleryImage
import isao.photorate.db.ImageEmbedding
import isao.photorate.db.PhotoRateDb
import isao.photorate.sqldelight.adapter.FloatArrayAdapter
import isao.photorate.sqldelight.adapter.PointsAdapter
import isao.photorate.sqldelight.adapter.ScoreAdapter
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

/**
 * SQLDelight database provider.
 *
 * Lives in this leaf module because the repositories that inject [PhotoRateDb]
 * live here too (the sqldelight schema and `isao.photorate.db` package moved to
 * this module as part of the module split). See [isao.photorate.core.di.LogModule] for why bindings
 * used by a leaf module must be defined in that module.
 */
@Module(includes = [DatabasePlatformModule::class])
class DatabaseModule {
    @Single
    fun providePhotoRateDb(driver: SqlDriver): PhotoRateDb = PhotoRateDb(
        driver,
        DetectedHandAdapter = DetectedHand.Adapter(
            scoreAdapter = ScoreAdapter,
            pointsAdapter = PointsAdapter,
        ),
        GalleryImageAdapter = GalleryImage.Adapter(EnumColumnAdapter()),
        configAdapter = Config.Adapter(
            sort_byAdapter = EnumColumnAdapter(),
            date_header_modeAdapter = EnumColumnAdapter(),
        ),
        ImageEmbeddingAdapter = ImageEmbedding.Adapter(
            embeddingAdapter = FloatArrayAdapter,
        ),
    )
}
