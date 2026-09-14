package isao.photorate.galleryComponent

import isao.photorate.config.ConfigComponentModule
import isao.photorate.core.CoreModule
import isao.photorate.gallery.db.PhotoRateDb
import isao.photorate.galleryComponent.db.DetectedHandQueries
import isao.photorate.galleryComponent.db.GalleryImageQueries
import isao.photorate.galleryComponent.db.ImageEmbeddingQueries
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Provided

@Module(includes = [CoreModule::class, ConfigComponentModule::class])
@ComponentScan("isao.photorate.galleryComponent")
class GalleryComponentModule {
  @Factory
  fun provideGalleryImageQueries(@Provided db: PhotoRateDb): GalleryImageQueries =
    db.galleryImageQueries

  @Factory
  fun provideDetectedHandQueries(@Provided db: PhotoRateDb): DetectedHandQueries =
    db.detectedHandQueries

  @Factory
  fun provideImageEmbeddingQueries(@Provided db: PhotoRateDb): ImageEmbeddingQueries =
    db.imageEmbeddingQueries
}
