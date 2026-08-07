package isao.photorate.galleryComponent.classify

import isao.photorate.inference.classify.LandmarkedImage.Point

/**
 * Serializes [List<Point>] to/from a compact ByteArray for BLOB storage. Format:
 * [count: Int (4 bytes LE)] [x: Float, y: Float, z: Float] * count (each 4 bytes LE) Total size:
 * 4 + 12 * count bytes.
 */
object PointsSerializer {

  fun serialize(points: List<Point>): ByteArray {
    val size = 4 + points.size * 12
    val bytes = ByteArray(size)
    // Write point count
    bytes[0] = (points.size shr 0).toByte()
    bytes[1] = (points.size shr 8).toByte()
    bytes[2] = (points.size shr 16).toByte()
    bytes[3] = (points.size shr 24).toByte()
    // Write points
    var offset = 4
    for (point in points) {
      writeFloat(
        bytes,
        offset,
        point.x,
      )
      offset += 4
      writeFloat(
        bytes,
        offset,
        point.y,
      )
      offset += 4
      writeFloat(
        bytes,
        offset,
        point.z,
      )
      offset += 4
    }
    return bytes
  }

  fun deserialize(bytes: ByteArray): List<Point> {
    if (bytes.size < 4) return emptyList()
    val count = readInt(bytes, 0)
    val expected = 4 + count * 12
    if (bytes.size < expected) return emptyList()
    val points = ArrayList<Point>(count)
    var offset = 4
    repeat(count) {
      val x =
        readFloat(
          bytes,
          offset,
        )
      offset += 4
      val y =
        readFloat(
          bytes,
          offset,
        )
      offset += 4
      val z =
        readFloat(
          bytes,
          offset,
        )
      offset += 4
      points.add(Point(x, y, z))
    }
    return points
  }

  private fun writeFloat(bytes: ByteArray, offset: Int, value: Float) {
    val bits = value.toRawBits()
    bytes[offset] = (bits shr 0).toByte()
    bytes[offset + 1] = (bits shr 8).toByte()
    bytes[offset + 2] = (bits shr 16).toByte()
    bytes[offset + 3] = (bits shr 24).toByte()
  }

  private fun readFloat(bytes: ByteArray, offset: Int): Float {
    val bits =
      (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    return Float.fromBits(bits)
  }

  private fun readInt(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
      ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
      ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
      ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
