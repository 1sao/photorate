package isao.photorate.imageRecognition.classify

import arrow.core.raise.Raise
import isao.photorate.imageRecognition.ResourceFailure

/**
 * Decodes a gallery URI whose shortest edge is around 'maxDimension', for later use in ML
 * processing.
 */
interface LandmarkImageLoader {
  context(_: Raise<ResourceFailure>)
  fun load(uri: String, minDimension: Int): LandmarkCandidate
}
