package isao.photorate.imageRecognition.mediapipe

import android.graphics.Bitmap
import isao.photorate.imageRecognition.classify.HandLandmarker
import isao.photorate.imageRecognition.classify.LandmarkedImage

/**
 * Detects hands directly from a [Bitmap] (the common contract's Android candidate is the bitmap
 * itself). Kept as an extension so the MediaPipe dataset tests read naturally; returns null when
 * inference fails.
 */
fun HandLandmarker.detectFromBitmap(bitmap: Bitmap): LandmarkedImage? =
  try {
    detect(bitmap)
  } catch (_: Exception) {
    null
  }
