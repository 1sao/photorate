package isao.photorate.galleryOld

import isao.photorate.photosComponent.classify.LandmarkCandidate
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHFetchOptions
import platform.Photos.PHImageContentModeDefault
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHPhotoLibrary
import swiftPMImport.PhotoRate.shared.MPPImage

class IosGalleryDataSource : GalleryDataSource {

    suspend fun requestPermission(): Boolean {
        // Check current status first
        if (PHPhotoLibrary.authorizationStatus() == PHAuthorizationStatusAuthorized) {
            return true
        }
        // Request permission
        var result = false
        PHPhotoLibrary.requestAuthorization { status ->
            result = status == PHAuthorizationStatusAuthorized
        }
        return result
    }

    override suspend fun fetchAllImageRefs(): List<GalleryImageRef> {
        val refs = mutableListOf<GalleryImageRef>()
        val fetchOptions = PHFetchOptions()

        val result = PHAsset.fetchAssetsWithMediaType(PHAssetMediaTypeImage, fetchOptions)
        val count = result.count.toInt()

        for (index in 0 until count) {
            val asset = result.objectAtIndex(index.toULong()) as PHAsset
            refs.add(
                GalleryImageRef(
                    id = asset.localIdentifier,
                    uriString = asset.localIdentifier,
                    dateAdded = 0L,
                ),
            )
        }
        return refs
    }

    override fun observeNewImages(): Flow<GalleryImageRef> = callbackFlow {
        // Stub: return empty flow. iOS observer requires delegate pattern.
        // TODO: Implement PHPhotoLibraryChangeObserver for live updates
        awaitClose { }
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun loadCandidate(ref: GalleryImageRef): LandmarkCandidate {
        val fetchResult = PHAsset.fetchAssetsWithLocalIdentifiers(
            listOf(ref.id),
            null,
        )
        val asset = fetchResult.firstObject as? PHAsset
            ?: throw IllegalArgumentException("Asset not found: ${ref.id}")

        // Use PHImageManager to get image data synchronously
        val options = PHImageRequestOptions().apply {
            synchronous = true
            deliveryMode = 1 // PHImageRequestOptionsDeliveryModeOpportunistic
        }

        var resultImage: MPPImage? = null

        PHImageManager.defaultManager().requestImageForAsset(
            asset,
            platform.CoreGraphics.CGSizeMake(1024.0, 1024.0),
            PHImageContentModeDefault,
            options,
        ) { result, info ->
            result?.let { uiImage ->
                resultImage = MPPImage(uiImage, null)
            }
        }

        return resultImage ?: throw IllegalArgumentException("Failed to load image: ${ref.id}")
    }
}
