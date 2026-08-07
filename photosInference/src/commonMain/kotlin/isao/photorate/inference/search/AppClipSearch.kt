package isao.photorate.inference.search

/**
 * MobileCLIP image-search pipeline (Android for now).
 *
 * Mirrors the hand-landmarker architecture: a platform-agnostic contract in commonMain
 * ([AppClipSearchFactory] / [AppClipSearch]) with a platform implementation behind it. On Android
 * the implementation wraps ONNX Runtime sessions for the MobileCLIP text + vision encoders and a
 * custom CLIP tokenizer (see [ClipTokenizer]); it is wired into Koin from the shared module's
 * [isao.photorate.PlatformModule].
 */

/**
 * Creates [AppClipSearch] instances. Platform implementations live in `androidMain` (and later
 * `iosMain`) and are provided through `PlatformModule` so the common code never touches ORT
 * directly.
 */
interface AppClipSearchFactory {
  fun createFromOptions(options: Options): AppClipSearch

  data class Options(
    /** CLIP text context length. The exported text model is fixed at 77. */
    val contextLength: Int = 77
  )
}

/**
 * A live MobileCLIP session: encodes text queries and images into shared 512-dim embedding space.
 * Implementations must be closed after use.
 */
interface AppClipSearch : AutoCloseable {
  /** Embeds a text query (e.g. "Turtle") into a 512-dim vector. */
  fun embedText(text: String): FloatArray

  /** Embeds the bytes of an image file (JPEG/PNG) into a 512-dim vector. */
  fun embedImage(imageBytes: ByteArray): FloatArray
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
