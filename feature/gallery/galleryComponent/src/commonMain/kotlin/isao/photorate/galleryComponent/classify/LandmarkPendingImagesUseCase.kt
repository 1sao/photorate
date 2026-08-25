package isao.photorate.galleryComponent.classify

import co.touchlab.kermit.Logger
import isao.photorate.gallery.db.DetectedHand
import isao.photorate.gallery.db.GalleryImage
import isao.photorate.gallery.db.GalleryImageStatus
import isao.photorate.galleryRepository.DetectedHandRepository
import isao.photorate.galleryRepository.GalleryImageRepository
import isao.photorate.imageRecognition.classify.GestureRecognizer
import isao.photorate.imageRecognition.classify.GestureRecognizerProvider
import isao.photorate.imageRecognition.classify.HandLandmarker
import isao.photorate.imageRecognition.classify.HandLandmarkerOptions
import isao.photorate.imageRecognition.classify.LandmarkCandidate
import isao.photorate.imageRecognition.classify.LandmarkImageLoader
import isao.photorate.imageRecognition.classify.LandmarkedImage
import isao.photorate.imageRecognition.classify.LandmarkerFactoryProvider
import isao.photorate.imageRecognition.classify.heightPx
import isao.photorate.imageRecognition.classify.widthPx
import isao.photorate.tracking.CrashReporter
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
 * Landmarks every unprocessed gallery image with the ACTIVE hand-landmark model, then stores the
 * rated hands. Model-agnostic: the landmarker and the image loader come from the
 * [LandmarkerFactoryProvider] seam, so swapping the on-device model (MediaPipe / ONNX) never
 * touches the scan pipeline. MediaPipe GPU inference + image decode must not run on the main thread
 * (it blocks the UI and was implicated in native crashes), so the whole scan runs on the default
 * dispatcher.
 */
@Factory
class LandmarkPendingImagesUseCase(
  private val galleryImageRepository: GalleryImageRepository,
  private val detectedHandRepository: DetectedHandRepository,
  @Provided private val landmarkerFactoryProvider: LandmarkerFactoryProvider,
  @Provided private val gestureRecognizerProvider: GestureRecognizerProvider,
  private val imageLoader: LandmarkImageLoader,
  @Provided private val crashReporter: CrashReporter,
  private val log: Logger,
) {
  suspend operator fun invoke() =
    withContext(Dispatchers.Default) {
      val model = landmarkerFactoryProvider.defaultModel
      val options = model.defaultOptions
      val recognizers = gestureRecognizerProvider.createRecognizers()
      landmarkerFactoryProvider.factoryFor(model).createFromOptions(options).use { landmarker ->
        do {
          var unprocessed = galleryImageRepository.getUnprocessedImages().first()

          measureTime {
            log.i { "Landmarking ${unprocessed.size} unprocessed images ($model)..." }
            unprocessed.forEach { image ->
              landmarkAndSaveOrNull(
                landmarker,
                image,
                recognizers,
                options,
              )
              yield()
            }
          }
            .also { log.i { "Landmarking completed in ${it.inWholeMilliseconds} ms" } }

          unprocessed = galleryImageRepository.getUnprocessedImages().first()
        } while (unprocessed.isNotEmpty() && isActive)
      }
    }

  private suspend fun landmarkAndSaveOrNull(
    landmarker: HandLandmarker,
    image: GalleryImage,
    recognizers: List<GestureRecognizer<*>>,
    options: HandLandmarkerOptions,
  ) {
    galleryImageRepository.updateStatus(
      image.uri,
      GalleryImageStatus.PROCESSING,
    )
    detectedHandRepository.deleteRealHandsForImage(image.uri)

    val candidate =
      imageLoader.load(
        image.uri,
        options.preferredImageDimension,
      )
    if (candidate == null) {
      log.e { "Failed to load image: ${image.uri}" }
      galleryImageRepository.updateStatus(
        image.uri,
        GalleryImageStatus.FAILED,
      )
      return
    }

    val gestureResults = runCatching {
      landmarker.detectWithRecognizers(candidate, recognizers)
    }
      .getOrElse { error ->
        crashReporter.logNonFatal(error, "landmark_failed", mapOf("uri" to image.uri))
        log.e { "Landmarking failed for uri: ${image.uri}. Reason: $error" }
        galleryImageRepository.updateStatus(
          image.uri,
          GalleryImageStatus.FAILED,
        )
        return
      }

    galleryImageRepository.markDone(
      uri = image.uri,
      detectedInMs = 0,
    )
    gestureResults
      .filter { it.score != null }
      .forEachIndexed { index, result ->
        val hand = result.hand
        val score = result.score!!
        val displayHand =
          unrotateAndNormalize(
            hand,
            candidate,
          )
        val handFeatures = HandFeatureExtractor.extract(displayHand)
        detectedHandRepository.insertHand(
          DetectedHand(
            id = -1,
            imageUri = image.uri,
            handIndex = index.toLong(),
            score = score,
            bboxMinX = handFeatures.bboxMinX.toDouble(),
            bboxMinY = handFeatures.bboxMinY.toDouble(),
            bboxMaxX = handFeatures.bboxMaxX.toDouble(),
            bboxMaxY = handFeatures.bboxMaxY.toDouble(),
            bboxAreaFraction = handFeatures.bboxAreaFraction.toDouble(),
            centroidX = handFeatures.centroidX.toDouble(),
            centroidY = handFeatures.centroidY.toDouble(),
            points = displayHand.points,
            uncertain = hand.uncertain || result.confidence < 1f,
            isUserRated = false,
          ),
        )
      }
  }

  /**
   * Maps a hand's points from its rating frame to true image space: rotate by
   * +[LandmarkedImage.Hand.rotationDegrees] around the hand bbox center (deg 0 is identity), then
   * normalize to 0..1 by the image dimensions for display/storage. Verified sign in
   * landmark_stage.md.
   */
  private fun unrotateAndNormalize(
    hand: LandmarkedImage.Hand,
    candidate: LandmarkCandidate,
  ): LandmarkedImage.Hand {
    val deg = hand.rotationDegrees
    val points = hand.points
    val rotated =
      if (deg == 0f) {
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
      }
    )
  }
}
