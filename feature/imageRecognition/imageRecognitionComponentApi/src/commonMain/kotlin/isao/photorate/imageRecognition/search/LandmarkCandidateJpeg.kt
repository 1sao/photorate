package isao.photorate.imageRecognition.search

import isao.photorate.imageRecognition.landmark.LandmarkCandidate

/** JPEG quality (0-100) used when encoding [LandmarkCandidate]s for CLIP vision encoding. */
const val CLIP_JPEG_QUALITY = 95

/**
 * Encodes the platform image (Bitmap on Android, UIImage on iOS, BufferedImage on JVM) into JPEG
 * bytes so the CLIP vision encoder can consume it.
 */
expect fun LandmarkCandidate.toJpegBytes(): ByteArray
