package isao.photorate.imageRecognition.search

import isao.photorate.imageRecognition.landmark.LandmarkCandidate
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
actual fun LandmarkCandidate.toJpegBytes(): ByteArray {
  val data =
    UIImageJPEGRepresentation(this, CLIP_JPEG_QUALITY / 100.0)
      ?: error("Failed to encode image to JPEG")
  return ByteArray(data.length.toInt()).apply {
    usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
  }
}
