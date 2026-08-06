package isao.photorate.photosComponent.classify

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.UIKit.UIImage

actual typealias LandmarkCandidate = UIImage

@OptIn(ExperimentalForeignApi::class)
actual val LandmarkCandidate.widthPx: Int
    get() = size.useContents { width.toInt() }

@OptIn(ExperimentalForeignApi::class)
actual val LandmarkCandidate.heightPx: Int
    get() = size.useContents { height.toInt() }
