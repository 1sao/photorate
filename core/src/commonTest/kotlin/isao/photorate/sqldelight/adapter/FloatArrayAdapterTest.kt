package isao.photorate.sqldelight.adapter

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Round-trip tests for [FloatArrayAdapter] — the byte packing that stores CLIP
 * embeddings (512 floats) as a BLOB. A byte-order/shift bug here would silently
 * corrupt every stored embedding (searches would rank garbage), so encode →
 * decode must reproduce the exact bit patterns.
 */
class FloatArrayAdapterTest {

    @Test
    fun `round-trips an embedding-sized float array`() {
        val floats = FloatArray(512) { index ->
            ((index - 256) % 13) * 0.5f // covers negatives, fractions, small values
        }
        assertContentEquals(floats, FloatArrayAdapter.decode(FloatArrayAdapter.encode(floats)))
    }

    @Test
    fun `round-trips edge values bitwise`() {
        val floats = floatArrayOf(
            0f,
            -0f,
            1f,
            -1f,
            0.5f,
            -2.75f,
            Float.MAX_VALUE,
            -Float.MAX_VALUE,
            Float.MIN_VALUE,
            Float.NaN, // contentEquals uses == (NaN != NaN), so compare bitwise
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
        )
        val decoded = FloatArrayAdapter.decode(FloatArrayAdapter.encode(floats))
        assertEquals(floats.size, decoded.size)
        floats.indices.forEach { index ->
            assertEquals(
                floats[index].toRawBits(),
                decoded[index].toRawBits(),
                "bit pattern changed at index $index",
            )
        }
    }
}
