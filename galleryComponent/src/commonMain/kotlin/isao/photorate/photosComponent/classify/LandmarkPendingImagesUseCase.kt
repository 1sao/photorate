package isao.photorate.galleryComponent.classify

import co.touchlab.kermit.Logger
import isao.photorate.gallery.db.DetectedHand
import isao.photorate.gallery.db.GalleryImage
import isao.photorate.gallery.db.GalleryImageStatus
import isao.photorate.galleryRepository.DetectedHandRepository
import isao.photorate.galleryRepository.GalleryImageRepository
import isao.photorate.inference.classify.HandLandmarker
import isao.photorate.inference.classify.HandLandmarkerOptions
import isao.photorate.inference.classify.LandmarkCandidate
import isao.photorate.inference.classify.LandmarkImageLoader
import isao.photorate.inference.classify.LandmarkedImage
import isao.photorate.inference.classify.LandmarkerFactoryProvider
import isao.photorate.inference.classify.heightPx
import isao.photorate.inference.classify.widthPx
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.measureTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Landmarks every unprocessed gallery image with the ACTIVE hand-landmark
 * model, then stores the rated hands. Model-agnostic: the landmarker and the
 * image loader come from the [LandmarkerFactoryProvider] seam, so swapping the
 * on-device model (MediaPipe / ONNX) never touches the scan pipeline.
 * MediaPipe GPU inference + image decode must not run on the main thread (it
 * blocks the UI and was implicated in native crashes), so the whole scan runs
 * on the default dispatcher.
 */
@Factory
class LandmarkPendingImagesUseCase(
    private val galleryImageRepository: GalleryImageRepository,
    private val detectedHandRepository: DetectedHandRepository,
    // The seam is bound by the root PlatformModule (shared) — the leaf
    // module cannot see it, so mark it external.
    @Provided private val landmarkerFactoryProvider: LandmarkerFactoryProvider,
    private val imageLoader: LandmarkImageLoader,
    private val landmarkRaterProvider: LandmarkRaterProvider,
    private val log: Logger,
) {
    suspend operator fun invoke() = withContext(Dispatchers.Default) {
        // TODO creating landmarker for nothing if there are no images
        val model = landmarkerFactoryProvider.defaultModel
        val options = model.defaultOptions
        landmarkerFactoryProvider.factoryFor(model).createFromOptions(options).use { landmarker ->
            val rater = landmarkRaterProvider.raterForScan()
            do {
                var unprocessed = galleryImageRepository.getUnprocessedImages().first()

                measureTime {
                    log.i { "Landmarking ${unprocessed.size} unprocessed images ($model)..." }
                    unprocessed.forEach { image ->
                        landmarkAndSaveOrNull(landmarker, image, rater, options)
                        yield()
                    }
                }.also {
                    log.i { "Landmarking completed in ${it.inWholeMilliseconds} ms" }
                }

                unprocessed = galleryImageRepository.getUnprocessedImages().first()
            } while (unprocessed.isNotEmpty() && isActive)
        }
    }

    private suspend fun landmarkAndSaveOrNull(
        landmarker: HandLandmarker,
        image: GalleryImage,
        rater: LandmarkRater,
        options: HandLandmarkerOptions,
    ) {
        galleryImageRepository.updateStatus(image.uri, GalleryImageStatus.PROCESSING)
        // This run is authoritative: drop the previous real detections (the
        // GalleryImage upsert no longer cascade-deletes them), keeping user ratings.
        detectedHandRepository.deleteRealHandsForImage(image.uri)

        val candidate = imageLoader.load(image.uri, options.preferredImageDimension)
        if (candidate == null) {
            log.e { "Failed to load image: ${image.uri}" }
            galleryImageRepository.updateStatus(image.uri, GalleryImageStatus.FAILED)
            return
        }

        val detection = runCatching { landmarker.detect(candidate) }
            .getOrElse { error ->
                log.e { "Landmarking failed for uri: ${image.uri}. Reason: $error" }
                galleryImageRepository.updateStatus(image.uri, GalleryImageStatus.FAILED)
                return
            }

        val rated = detection.hands.mapNotNull { hand ->
            rater.rate(hand)?.let { rating -> hand to rating }
        }
        galleryImageRepository.markDone(
            uri = image.uri,
            detectedInMs = detection.detectedInMs,
        )
        rated.forEachIndexed { index, (hand, rating) ->
            // The rated hand is in image-pixel space (isotropic, so the gesture
            // classifier's ratios/angles are valid); un-rotate rotated crops
            // (ONNX rotation search) and normalize to 0..1 for display geometry.
            val displayHand = unrotateAndNormalize(hand, candidate)
            val handFeatures = HandFeatureExtractor.extract(displayHand)
            detectedHandRepository.insertHand(
                DetectedHand(
                    id = -1,
                    imageUri = image.uri,
                    handIndex = index.toLong(),
                    score = rating.score,
                    bboxMinX = handFeatures.bboxMinX.toDouble(),
                    bboxMinY = handFeatures.bboxMinY.toDouble(),
                    bboxMaxX = handFeatures.bboxMaxX.toDouble(),
                    bboxMaxY = handFeatures.bboxMaxY.toDouble(),
                    bboxAreaFraction = handFeatures.bboxAreaFraction.toDouble(),
                    centroidX = handFeatures.centroidX.toDouble(),
                    centroidY = handFeatures.centroidY.toDouble(),
                    points = displayHand.points,
                    // A best-guess rating (confidence < 1, dev mode) marks the
                    // hand uncertain; the pipeline's keypoint-based tier still
                    // applies too.
                    uncertain = hand.uncertain || rating.confidence < 1f,
                    isUserRated = false,
                ),
            )
        }
    }

    /**
     * Maps a hand's points from its rating frame to true image space: rotate by
     * +[LandmarkedImage.Hand.rotationDegrees] around the hand bbox center (deg
     * 0 is identity), then normalize to 0..1 by the image dimensions for
     * display/storage. Verified sign in landmark_stage.md.
     */
    private fun unrotateAndNormalize(hand: LandmarkedImage.Hand, candidate: LandmarkCandidate): LandmarkedImage.Hand {
        val deg = hand.rotationDegrees
        val points = hand.points
        val rotated = if (deg == 0f) {
            points
        } else {
            val cx = (points.minOf { it.x } + points.maxOf { it.x }) / 2f
            val cy = (points.minOf { it.y } + points.maxOf { it.y }) / 2f
            val theta = deg.toDouble() * PI / 180.0
            val cosT = cos(theta).toFloat()
            val sinT = sin(theta).toFloat()
            points.map { p ->
                val dx = p.x - cx
                val dy = p.y - cy
                LandmarkedImage.Point(
                    x = cosT * dx - sinT * dy + cx,
                    y = sinT * dx + cosT * dy + cy,
                    z = p.z,
                )
            }
        }
        val safeW = candidate.widthPx.coerceAtLeast(1)
        val safeH = candidate.heightPx.coerceAtLeast(1)
        return LandmarkedImage.Hand(
            rotated.map { p ->
                LandmarkedImage.Point(
                    x = p.x / safeW,
                    y = p.y / safeH,
                    z = p.z,
                )
            },
        )
    }
}
