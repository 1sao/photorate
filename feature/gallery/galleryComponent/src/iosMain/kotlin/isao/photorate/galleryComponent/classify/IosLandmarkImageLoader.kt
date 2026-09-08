package isao.photorate.galleryComponent.classify

import arrow.core.raise.Raise
import arrow.core.raise.context.raise
import isao.photorate.imageRecognition.ResourceFailure
import isao.photorate.imageRecognition.classify.LandmarkCandidate
import isao.photorate.imageRecognition.classify.LandmarkImageLoader
import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.core.annotation.Factory
import platform.CoreGraphics.CGSizeMake
import platform.Photos.PHAsset
import platform.Photos.PHImageContentModeDefault
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestOptions
import platform.UIKit.UIImage

/**
 * iOS [LandmarkImageLoader]: gallery URIs are PHAsset local identifiers, so load the asset via
 * PHImageManager into a [UIImage] (the common candidate on iOS).
 */
@OptIn(ExperimentalForeignApi::class)
@Factory
class IosLandmarkImageLoader : LandmarkImageLoader {
  context(_: Raise<ResourceFailure>)
  override fun load(
    uri: String,
    minDimension: Int,
  ): LandmarkCandidate {
    val fetchResult = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(uri), null)
    val asset = fetchResult.firstObject as? PHAsset ?: raise(ResourceFailure.NotFound(uri))
    val options =
      PHImageRequestOptions().apply {
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
    return resultImage ?: raise(ResourceFailure.DecodeFailed(uri))
  }
}
