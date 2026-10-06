package isao.photorate.galleryComponent.domain

import co.touchlab.kermit.Logger
import org.koin.core.annotation.Single

@Single
class GalleryResetManager(
  private val gallerySyncManager: GallerySyncManager,
  private val galleryImageRepository: GalleryImageRepository,
  private val log: Logger,
) {
  suspend fun resetAndRescan() {
    log.i("Resetting gallery: removing non-user-given scores")
    galleryImageRepository.deleteNonUserGivenScores()
    log.i("Rescanning gallery after reset")
    gallerySyncManager.run(requestType = SystemGalleryImageRepository.RequestType.FULL_SCAN)
  }
}
