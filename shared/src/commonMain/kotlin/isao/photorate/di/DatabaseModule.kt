package isao.photorate.di

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.SqlDriver
import isao.photorate.config.db.Config
import isao.photorate.gallery.db.PhotoRateDb
import isao.photorate.galleryComponent.data.adapter.PointsAdapter
import isao.photorate.galleryComponent.data.adapter.ScoreAdapter
import isao.photorate.galleryComponent.db.DetectedHand
import isao.photorate.galleryComponent.db.GalleryImage
import isao.photorate.galleryComponent.db.ImageEmbedding
import isao.photorate.search.db.PhotoRateDb as SearchPhotoRateDb
import isao.photorate.sqldelight.adapter.FloatArrayAdapter
import org.koin.core.annotation.Module
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Module
class DatabaseModule {
  @Single(binds = [Transacter::class])
  fun provideGalleryDb(@Provided driver: SqlDriver): PhotoRateDb =
    PhotoRateDb(
      driver,
      DetectedHandAdapter =
        DetectedHand.Adapter(
          scoreAdapter = ScoreAdapter,
          pointsAdapter = PointsAdapter,
        ),
      GalleryImageAdapter = GalleryImage.Adapter(EnumColumnAdapter()),
      configAdapter =
        Config.Adapter(
          sort_byAdapter = EnumColumnAdapter(),
          date_header_modeAdapter = EnumColumnAdapter(),
        ),
      ImageEmbeddingAdapter = ImageEmbedding.Adapter(embeddingAdapter = FloatArrayAdapter),
    )

  @Single
  fun provideSearchDb(@Provided driver: SqlDriver): SearchPhotoRateDb =
    SearchPhotoRateDb(
      driver,
      DetectedHandAdapter =
        DetectedHand.Adapter(
          scoreAdapter = ScoreAdapter,
          pointsAdapter = PointsAdapter,
        ),
      GalleryImageAdapter = GalleryImage.Adapter(EnumColumnAdapter()),
      configAdapter =
        Config.Adapter(
          sort_byAdapter = EnumColumnAdapter(),
          date_header_modeAdapter = EnumColumnAdapter(),
        ),
      ImageEmbeddingAdapter = ImageEmbedding.Adapter(embeddingAdapter = FloatArrayAdapter),
    )
}
