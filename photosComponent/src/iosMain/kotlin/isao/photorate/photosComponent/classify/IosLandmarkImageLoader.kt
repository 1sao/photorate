package isao.photorate.photosComponent.classify

import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.core.annotation.Factory
import platform.CoreGraphics.CGSizeMake
import platform.Photos.PHAsset
import platform.Photos.PHImageContentModeDefault
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestOptions
import platform.UIKit.UIImage

/**
 * iOS [LandmarkImageLoader]: gallery URIs are PHAsset local identifiers, so
 * load the asset via PHImageManager into a [UIImage] (the common candidate on
 * iOS). Mirrors the galleryOld data source's loader; the iOS scan pipeline is
 * still dormant, so this stays placeholder-quality.
 */
@OptIn(ExperimentalForeignApi::class)
@Factory
class IosLandmarkImageLoader : LandmarkImageLoader {

    override fun load(uri: String, maxDimension: Int): LandmarkCandidate? {
        val fetchResult = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(uri), null)
        val asset = fetchResult.firstObject as? PHAsset ?: return null
        val options = PHImageRequestOptions().apply {
            synchronous = true
            deliveryMode = 1 // PHImageRequestOptionsDeliveryModeOpportunistic
        }
        var resultImage: UIImage? = null
        PHImageManager.defaultManager().requestImageForAsset(
            asset,
            CGSizeMake(1024.0, 1024.0),
            PHImageContentModeDefault,
            options,
        ) { result, _ ->
            result?.let { resultImage = it }
        }
        return resultImage
    }
}
