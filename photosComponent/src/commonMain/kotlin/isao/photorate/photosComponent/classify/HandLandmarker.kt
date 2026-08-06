package isao.photorate.photosComponent.classify

/**
 * Platform image a hand-landmark model consumes: a Bitmap on Android, a
 * UIImage on iOS. Each provider adapts it to its native input internally
 * (MediaPipe wraps it in an MPImage; ONNX consumes it directly).
 */
expect class LandmarkCandidate

/** Width in pixels (used to normalize landmark points to 0..1 for storage). */
expect val LandmarkCandidate.widthPx: Int

/** Height in pixels (used to normalize landmark points to 0..1 for storage). */
expect val LandmarkCandidate.heightPx: Int

/**
 * Model-agnostic options for a hand-landmark run. Fields are per-model: each
 * provider reads the ones it knows and ignores the rest (MediaPipe uses the
 * presence confidence, ONNX the keypoint floors).
 */
data class HandLandmarkerOptions(
    val maxNumHands: Int,
    val minHandDetectionConfidence: Float,
    /** MediaPipe palm-detection presence gate (unused by ONNX). */
    val minHandPresenceConfidence: Float = 0f,
    /** ONNX mean-keypoint floor below which a box is not a hand (unused by MediaPipe). */
    val minHandKpConfidence: Float = 0f,
    /** ONNX confident gate for ROCK/OK (unused by MediaPipe). */
    val minHandConfidentKpConfidence: Float = 0f,
    /** Decode target for the shortest image edge (the scan passes it to [LandmarkImageLoader]). */
    val preferredImageDimension: Int = 224,
)

/**
 * Detects the hands in one image. Returned hands are in IMAGE-PIXEL space — an
 * isometry of the crop the gesture classifier was tuned on, so its ratios and
 * angles are undistorted regardless of image aspect. The scan un-rotates (via
 * [LandmarkedImage.Hand.rotationDegrees], 0 for MediaPipe) and normalizes to
 * 0..1 for storage.
 */
interface HandLandmarker : AutoCloseable {
    fun detect(candidate: LandmarkCandidate): LandmarkedImage
}

interface HandLandmarkerFactory {
    fun createFromOptions(options: HandLandmarkerOptions): HandLandmarker
}

/**
 * The on-device hand-landmark models the app ships. Each provider module
 * implements [HandLandmarkerFactory] for its model; [LandmarkerFactoryProvider]
 * resolves the active one.
 */
enum class LandmarkModel(val id: String, val displayName: String) {
    MEDIAPIPE("mediapipe", "MediaPipe"),
    ONNX("onnx", "ONNX"),
    LITERT("litert", "LiteRT"),
    ;

    /** App-tuned default options for this model (thresholds in plans/benchmarks). */
    val defaultOptions: HandLandmarkerOptions
        get() = when (this) {
            MEDIAPIPE -> HandLandmarkerOptions(
                maxNumHands = 2,
                minHandDetectionConfidence = 0.35f,
                minHandPresenceConfidence = 0.35f,
            )

            ONNX -> HandLandmarkerOptions(
                maxNumHands = 2,
                minHandDetectionConfidence = 0.25f,
                minHandKpConfidence = 0.3f,
                // ROCK/OK below this mean-keypoint score are still rated but
                // flagged uncertain (best guess); restored from the old
                // OnnxLandmarkerOptions default (0.45) — the shared options
                // struct defaults it to 0f, which made every ROCK/OK above
                // the kp floor confident and silently changed the uncertain
                // tier on-device (see OnnxHandLandmarkDatasetTest expectations).
                minHandConfidentKpConfidence = 0.45f,
                preferredImageDimension = 640,
            )

            // The LiteRT pipeline runs the same RTMDet + RTMPose models on the
            // LiteRT runtime (ml/litert/ recipe), so its tuned gates match the
            // ONNX provider's.
            LITERT -> HandLandmarkerOptions(
                maxNumHands = 2,
                minHandDetectionConfidence = 0.25f,
                minHandKpConfidence = 0.3f,
                minHandConfidentKpConfidence = 0.45f,
                preferredImageDimension = 640,
            )
        }
}

/**
 * The selection seam for switching between local models: resolves a model to
 * its factory. Implemented per platform in `shared`'s PlatformModule — swap
 * [defaultModel] there to change what a scan runs (Android defaults to ONNX,
 * iOS to MediaPipe).
 */
interface LandmarkerFactoryProvider {
    val defaultModel: LandmarkModel
    fun factoryFor(model: LandmarkModel): HandLandmarkerFactory
}

/**
 * Concrete [LandmarkerFactoryProvider]: a fixed default model and one factory
 * per [LandmarkModel]. The platform module builds it with its own factory
 * implementations (ONNX on Android, MediaPipe on iOS).
 */
class DefaultLandmarkerFactoryProvider(
    override val defaultModel: LandmarkModel,
    private val factories: Map<LandmarkModel, HandLandmarkerFactory>,
) : LandmarkerFactoryProvider {
    override fun factoryFor(model: LandmarkModel): HandLandmarkerFactory =
        requireNotNull(factories[model]) { "No landmarker factory registered for $model" }
}
