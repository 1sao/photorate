package isao.photorate.galleryComponent.search

import isao.photorate.imageRecognition.search.cosineSimilarity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [cosineSimilarity] — the metric used to rank photos by how well their embeddings
 * match a text query.
 */
class CosineSimilarityTest {

  @Test
  fun `identical vectors have similarity 1`() {
    val v = floatArrayOf(1f, 2f, 3f)
    assertEquals(
      1.0,
      cosineSimilarity(v, v),
      absoluteTolerance = 1e-9,
    )
  }

  @Test
  fun `proportional vectors have similarity 1`() {
    val a = floatArrayOf(1f, 2f, 3f)
    val b = floatArrayOf(2f, 4f, 6f)
    assertEquals(
      1.0,
      cosineSimilarity(a, b),
      absoluteTolerance = 1e-9,
    )
  }

  @Test
  fun `orthogonal vectors have similarity 0`() {
    val a = floatArrayOf(1f, 0f, 0f)
    val b = floatArrayOf(0f, 1f, 0f)
    assertEquals(
      0.0,
      cosineSimilarity(a, b),
      absoluteTolerance = 1e-9,
    )
  }

  @Test
  fun `opposite vectors have similarity -1`() {
    val a = floatArrayOf(1f, 2f)
    val b = floatArrayOf(-1f, -2f)
    assertEquals(
      -1.0,
      cosineSimilarity(a, b),
      absoluteTolerance = 1e-9,
    )
  }

  @Test
  fun `zero vector yields similarity 0 instead of NaN`() {
    val zero = floatArrayOf(0f, 0f, 0f)
    val other = floatArrayOf(1f, 2f, 3f)
    val result = cosineSimilarity(zero, other)
    assertTrue(
      result.isFinite(),
      "zero vector must not produce NaN/Infinity",
    )
    assertEquals(0.0, result)
  }

  @Test
  fun `mismatched vector sizes throw`() {
    val a = floatArrayOf(1f, 2f, 3f)
    val b = floatArrayOf(1f, 2f)
    assertFailsWith<IllegalArgumentException> { cosineSimilarity(a, b) }
  }
}
