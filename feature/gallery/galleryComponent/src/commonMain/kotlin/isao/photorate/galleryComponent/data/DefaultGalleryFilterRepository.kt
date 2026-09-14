package isao.photorate.galleryComponent.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import isao.photorate.gallery.db.PhotoRateDb
import isao.photorate.galleryComponent.db.SelectImagesWithScores
import isao.photorate.galleryComponent.db.SelectMatching
import isao.photorate.galleryComponent.db.SelectUncertainImagesWithScore
import isao.photorate.galleryComponent.domain.GalleryFilterRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class DefaultGalleryFilterRepository(@Provided private val db: PhotoRateDb) :
  GalleryFilterRepository {
  // TODO inject specific queries.
  private val queries
    get() = db.galleryImageQueries

  override fun selectMatching(): Flow<List<SelectMatching>> =
    queries.selectMatching().asFlow().mapToList(Dispatchers.IO)

  override fun selectImagesWithScores(): Flow<List<SelectImagesWithScores>> =
    queries.selectImagesWithScores().asFlow().mapToList(Dispatchers.IO)

  override fun selectUncertainImages(): Flow<List<SelectUncertainImagesWithScore>> =
    queries.selectUncertainImagesWithScore().asFlow().mapToList(Dispatchers.IO)
}
