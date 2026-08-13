package isao.photorate.imageRecognition.classify

import java.awt.image.BufferedImage

actual typealias LandmarkCandidate = BufferedImage

actual val LandmarkCandidate.widthPx: Int
  get() = width
actual val LandmarkCandidate.heightPx: Int
  get() = height
