package isao.photorate.imageRecognition.landmark

import java.awt.image.BufferedImage

actual typealias LandmarkCandidate = BufferedImage

actual val LandmarkCandidate.widthPx: Int
  get() = width
actual val LandmarkCandidate.heightPx: Int
  get() = height
