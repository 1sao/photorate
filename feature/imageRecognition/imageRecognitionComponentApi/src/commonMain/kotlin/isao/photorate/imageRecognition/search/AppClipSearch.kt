package isao.photorate.imageRecognition.search

import isao.photorate.imageRecognition.landmark.LandmarkCandidate

interface AppClipSearchFactory {
  fun createFromOptions(options: Options): AppClipSearch

  data class Options(
    /** CLIP text context length. The exported text model is fixed at 77. */
    val contextLength: Int = 77
  )
}

/**
 * A live CLIP session: encodes text queries and images into shared 512-dim embedding space.
 * Implementations must be closed after use.
 */
interface AppClipSearch : AutoCloseable {
  /** Embeds a text query (e.g. "Coffee") into a vector. */
  fun embedText(text: String): FloatArray

  fun embedImage(imageBytes: ByteArray): FloatArray

  fun embedImage(candidate: LandmarkCandidate): FloatArray = embedImage(candidate.toJpegBytes())
}

/**
 * Cosine similarity between two equal-length vectors in [-1, 1]. Higher = more similar. Used to
 * rank images by how well they match a query.
 */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
  require(a.size == b.size) { "vector sizes differ: ${a.size} vs ${b.size}" }
  var dot = 0.0
  var normA = 0.0
  var normB = 0.0
  for (i in a.indices) {
    dot += a[i] * b[i]
    normA += a[i] * a[i]
    normB += b[i] * b[i]
  }
  if (normA == 0.0 || normB == 0.0) return 0.0
  return dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
}
