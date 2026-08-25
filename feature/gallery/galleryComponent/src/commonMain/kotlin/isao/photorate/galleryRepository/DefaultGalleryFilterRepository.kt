package isao.photorate.galleryRepository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import isao.photorate.gallery.db.PhotoRateDb
import isao.photorate.gallery.db.SelectMatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Factory

@Factory
class DefaultGalleryFilterRepository(private val db: PhotoRateDb) : GalleryFilterRepository {
  // TODO inject specific queries.
  private val queries
    get() = db.galleryImageQueries

  override fun selectMatching(): Flow<List<SelectMatching>> =
    queries.selectMatching().asFlow().mapToList(Dispatchers.IO)

  override fun selectGalleryImages(): Flow<List<GalleryImage>> =
    queries.galleryImages().asFlow().mapToList(Dispatchers.IO).map { rows ->
      rows.map { row ->
        GalleryImage(
          uri = row.uri,
          scannedAt = row.scannedAt,
          createdAt = row.createdAt,
          modifiedAt = row.modifiedAt,
          scores = row.scores,
          bestGuessScore = row.bestGuessScore?.toInt(),
          isUncertain = row.isUncertain != 0L,
        )
      }
    }
}
