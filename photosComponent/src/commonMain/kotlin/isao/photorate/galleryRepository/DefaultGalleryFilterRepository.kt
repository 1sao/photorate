package isao.photorate.galleryRepository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import isao.photorate.db.PhotoRateDb
import isao.photorate.db.SelectImagesWithScores
import isao.photorate.db.SelectMatching
import isao.photorate.db.SelectUncertainImagesWithScore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Factory

@Factory
class DefaultGalleryFilterRepository(private val db: PhotoRateDb) : GalleryFilterRepository {

    private val queries get() = db.galleryQueries

    override fun selectMatching(): Flow<List<SelectMatching>> = queries.selectMatching()
        .asFlow()
        .mapToList(Dispatchers.IO)

    override fun selectImagesWithScores(): Flow<List<SelectImagesWithScores>> = queries.selectImagesWithScores()
        .asFlow()
        .mapToList(Dispatchers.IO)

    override fun selectUncertainImages(): Flow<List<SelectUncertainImagesWithScore>> = queries.selectUncertainImagesWithScore()
        .asFlow()
        .mapToList(Dispatchers.IO)
}
