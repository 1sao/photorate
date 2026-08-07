package isao.photorate.searchComponent.di

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import isao.photorate.config.db.Config
import isao.photorate.gallery.db.DetectedHand
import isao.photorate.gallery.db.GalleryImage
import isao.photorate.gallery.db.ImageEmbedding
import isao.photorate.search.db.PhotoRateDb
import isao.photorate.sqldelight.adapter.FloatArrayAdapter
import isao.photorate.sqldelight.adapter.PointsAdapter
import isao.photorate.sqldelight.adapter.ScoreAdapter
import org.koin.core.annotation.Module
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

// The driver is `@Provided` — bound at the root by `/app` (Android) or
// `shared` iosMain (iOS).
@Module
class DatabaseModule {
    @Single
    fun provideSearchDb(@Provided driver: SqlDriver): PhotoRateDb = PhotoRateDb(
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
