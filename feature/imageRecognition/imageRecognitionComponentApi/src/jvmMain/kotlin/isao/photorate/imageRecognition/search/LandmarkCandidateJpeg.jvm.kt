package isao.photorate.imageRecognition.search

import isao.photorate.imageRecognition.landmark.LandmarkCandidate
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

actual fun LandmarkCandidate.toJpegBytes(): ByteArray =
  ByteArrayOutputStream().use { out ->
    check(ImageIO.write(this, "jpeg", out)) { "No JPEG writer available" }
    out.toByteArray()
  }
