package isao.photorate.galleryComponent.data

import arrow.core.raise.Raise
import arrow.core.raise.context.raise
import isao.photorate.galleryComponent.db.GalleryImage
import isao.photorate.galleryComponent.domain.GalleryImageStatus
import isao.photorate.galleryComponent.domain.GalleryScanResult
import isao.photorate.galleryComponent.domain.SystemGalleryImageRepository
import isao.photorate.galleryComponent.domain.SystemImageDetails
import isao.photorate.imageRecognition.ResourceFailure
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import platform.Foundation.NSData
import platform.Foundation.NSIndexSet
import platform.Foundation.NSKeyedArchiver
import platform.Foundation.NSKeyedUnarchiver
import platform.Foundation.NSMakeRange
import platform.Foundation.NSSortDescriptor
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHFetchOptions
import platform.Photos.PHFetchResult
import platform.Photos.PHObjectTypeAsset
import platform.Photos.PHPersistentChangeToken
import platform.Photos.PHPhotoLibrary

/**
 * iOS [SystemGalleryImageRepository] placeholder — the populate-from-gallery pipeline is
 * Android-only for now.
 */
@OptIn(ExperimentalForeignApi::class)
@Factory
class IosSystemGalleryImageRepository :
  SystemGalleryImageRepository<IosSystemGalleryImageRepository.Checkpoint> {
  override suspend fun getImages(
    requestType: SystemGalleryImageRepository.RequestType
  ): GalleryScanResult =
    withContext(Dispatchers.IO) {
      val lastChangeToken = getLastSavedToken()

      if (
        requestType == SystemGalleryImageRepository.RequestType.FULL_SCAN || lastChangeToken == null
      ) {
        val images =
          PHAsset.fetchAssetsWithMediaType(
              PHAssetMediaTypeImage,
              PHFetchOptions().apply {
                sortDescriptors =
                  listOf(NSSortDescriptor.sortDescriptorWithKey("creationDate", ascending = false))
              },
            )
            .toGalleryImages()

        return@withContext GalleryScanResult(
          images = images,
          checkpoint = Checkpoint(getCurrentToken()),
          isCompleteScan = true,
        )
      }

      val changes =
        PHPhotoLibrary.sharedPhotoLibrary().fetchPersistentChangesSinceToken(lastChangeToken, null)

      val identifiers = HashSet<String>()
      changes?.enumerateChangesWithBlock { change, stop ->
        val details = change?.changeDetailsForObjectType(PHObjectTypeAsset, null)
        identifiers +=
          details?.let { (it.insertedLocalIdentifiers + it.updatedLocalIdentifiers) as Set<String> }
            ?: emptySet()

        // TODO proactively react to already deleted images using this property.
        identifiers -= (details?.deletedLocalIdentifiers as Set<String>?) ?: emptySet()
      }
      val images =
        PHAsset.fetchAssetsWithLocalIdentifiers(identifiers.toList(), null).toGalleryImages()

      return@withContext GalleryScanResult(
        images = images,
        checkpoint = Checkpoint(getCurrentToken()),
        isCompleteScan = false,
      )
    }

  private fun PHFetchResult.toGalleryImages(): List<GalleryImage> {
    val assets = objectsAtIndexes(NSIndexSet(NSMakeRange(0u, count))) as List<PHAsset>
    return assets.map { asset ->
      GalleryImage(
        uri = asset.localIdentifier,
        createdAt = asset.creationDate?.timeIntervalSince1970?.toLong() ?: 0L,
        modifiedAt = asset.modificationDate?.timeIntervalSince1970?.toLong() ?: 0L,
        status = GalleryImageStatus.PENDING,
        scannedAt = null,
        detectedInMs = null,
      )
    }
  }

  override suspend fun saveCheckpoint(checkpoint: Checkpoint) {
    val encoded =
      NSKeyedArchiver.archivedDataWithRootObject(
        checkpoint.token,
        requiringSecureCoding = true,
        error = null,
      )
    NSUserDefaults.standardUserDefaults.setObject(encoded, forKey = LAST_CHANGE_TOKEN)
  }

  private fun getLastSavedToken(): PHPersistentChangeToken? =
    (NSUserDefaults.standardUserDefaults.objectForKey(LAST_CHANGE_TOKEN))?.let {
      NSKeyedUnarchiver.unarchiveObjectWithData(it as NSData) as PHPersistentChangeToken
    }

  private fun getCurrentToken(): PHPersistentChangeToken =
    PHPhotoLibrary.sharedPhotoLibrary().currentChangeToken

  context(_: Raise<ResourceFailure>)
  override suspend fun getImageDetails(uri: String): SystemImageDetails =
    raise(ResourceFailure.NotFound(uri))

  data class Checkpoint(val token: PHPersistentChangeToken) :
    SystemGalleryImageRepository.Checkpoint

  private companion object {
    const val LAST_CHANGE_TOKEN = "lastChangeToken"
  }
}
