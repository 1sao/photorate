package isao.photorate.galleryOld

import isao.photorate.inference.classify.LandmarkCandidate
import kotlinx.coroutines.flow.Flow

interface GalleryDataSource {
    suspend fun requestPermission(): Boolean
    suspend fun fetchAllImageRefs(): List<GalleryImageRef>
    fun observeNewImages(): Flow<GalleryImageRef>
    suspend fun loadCandidate(ref: GalleryImageRef): LandmarkCandidate
}
