package isao.photorate.photosComponent.classify

import android.graphics.Bitmap

actual typealias LandmarkCandidate = Bitmap

actual val LandmarkCandidate.widthPx: Int get() = width
actual val LandmarkCandidate.heightPx: Int get() = height
