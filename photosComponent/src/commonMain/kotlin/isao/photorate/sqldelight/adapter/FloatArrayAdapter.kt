package isao.photorate.sqldelight.adapter

import app.cash.sqldelight.ColumnAdapter

/**
 * Stores a [FloatArray] (e.g. a 512-dim CLIP embedding) as a little-endian
 * BLOB. Manual byte packing instead of java.nio so it works on every target.
 */
object FloatArrayAdapter : ColumnAdapter<FloatArray, ByteArray> {

    override fun decode(databaseValue: ByteArray): FloatArray {
        val count = databaseValue.size / FLOAT_BYTES
        val floats = FloatArray(count)
        for (i in 0 until count) {
            val offset = i * FLOAT_BYTES
            val bits = (databaseValue[offset].toInt() and 0xFF) or
                ((databaseValue[offset + 1].toInt() and 0xFF) shl 8) or
                ((databaseValue[offset + 2].toInt() and 0xFF) shl 16) or
                ((databaseValue[offset + 3].toInt() and 0xFF) shl 24)
            floats[i] = Float.fromBits(bits)
        }
        return floats
    }

    override fun encode(value: FloatArray): ByteArray {
        val bytes = ByteArray(value.size * FLOAT_BYTES)
        value.forEachIndexed { index, f ->
            val bits = f.toRawBits()
            val offset = index * FLOAT_BYTES
            bytes[offset] = (bits and 0xFF).toByte()
            bytes[offset + 1] = ((bits shr 8) and 0xFF).toByte()
            bytes[offset + 2] = ((bits shr 16) and 0xFF).toByte()
            bytes[offset + 3] = ((bits shr 24) and 0xFF).toByte()
        }
        return bytes
    }

    private const val FLOAT_BYTES = 4
}
