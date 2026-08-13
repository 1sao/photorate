package isao.photorate.imageRecognition.classify

/**
 * Decodes a gallery image for a hand-landmark run. Provider-agnostic: the [maxDimension] target
 * (shortest edge) comes from the active model's options — the MediaPipe pipeline historically
 * decoded at 224px and the ONNX one at 640px (larger crops keep more detail for the landmark
 * models).
 */
interface LandmarkImageLoader {
  /** Returns the decoded image, or null when it cannot be decoded. */
  fun load(uri: String, maxDimension: Int): LandmarkCandidate?
}
