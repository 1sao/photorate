package isao.photorate.imageRecognition.search

import android.graphics.Bitmap
import isao.photorate.imageRecognition.landmark.LandmarkCandidate
import java.io.ByteArrayOutputStream

actual fun LandmarkCandidate.toJpegBytes(): ByteArray =
  ByteArrayOutputStream().use { out ->
    compress(Bitmap.CompressFormat.JPEG, CLIP_JPEG_QUALITY, out)
    out.toByteArray()
  }
