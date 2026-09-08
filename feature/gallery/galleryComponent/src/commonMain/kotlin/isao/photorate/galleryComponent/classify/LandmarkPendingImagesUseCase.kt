package isao.photorate.galleryComponent.classify

import arrow.core.raise.ExperimentalRaiseAccumulateApi
import arrow.core.raise.RaiseAccumulate
import arrow.core.raise.context.forEachAccumulating
import arrow.core.raise.context.raise
import arrow.core.raise.iorNel
import arrow.core.raise.recover
import isao.photorate.gallery.db.DetectedHand
import isao.photorate.gallery.db.GalleryImage
import isao.photorate.galleryRepository.GalleryImageRepository
import isao.photorate.imageRecognition.ResourceFailure
import isao.photorate.imageRecognition.classify.GestureRecognizer
import isao.photorate.imageRecognition.classify.GestureRecognizerProvider
import isao.photorate.imageRecognition.classify.GestureResult
import isao.photorate.imageRecognition.classify.HandFeatures
import isao.photorate.imageRecognition.classify.HandLandmarker
import isao.photorate.imageRecognition.classify.HandLandmarkerOptions
import isao.photorate.imageRecognition.classify.LandmarkCandidate
import isao.photorate.imageRecognition.classify.LandmarkImageLoader
import isao.photorate.imageRecognition.classify.LandmarkedImage
import isao.photorate.imageRecognition.classify.LandmarkerFactoryProvider
import isao.photorate.imageRecognition.classify.heightPx
import isao.photorate.imageRecognition.classify.widthPx
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Clock
import kotlin.time.measureTimedValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Landmarks every unprocessed gallery image with the default hand landmarking model, then stores
 * the rated hands.
 *
 * Returns failures during landmarking for each image if any, and the number of processed images
 * (including failed ones).
 */
@Factory
class LandmarkPendingImagesUseCase(
  private val galleryImageRepository: GalleryImageRepository,
  @Provided private val landmarkerFactoryProvider: LandmarkerFactoryProvider,
  @Provided private val gestureRecognizerProvider: GestureRecognizerProvider,
  private val imageLoader: LandmarkImageLoader,
) {
  suspend operator fun invoke() = iorNel {
    withContext(Dispatchers.Default) {
      val model = landmarkerFactoryProvider.defaultModel
      val options = model.defaultOptions
      val recognizers = gestureRecognizerProvider.createRecognizers()

      var processedCount = 0

      landmarkerFactoryProvider.factoryFor(model).createFromOptions(options).use { landmarker ->
        do {
          var unprocessed = galleryImageRepository.getUnprocessedImages().first()
          forEachAccumulating(unprocessed) { image ->
            landmarkAndSave(
              landmarker,
              image,
              recognizers,
              options,
            )
            processedCount++
            yield()
          }

          unprocessed = galleryImageRepository.getUnprocessedImages().first()
        } while (unprocessed.isNotEmpty() && isActive)
      }

      return@withContext processedCount
    }
  }

  @OptIn(ExperimentalRaiseAccumulateApi::class)
  context(_: RaiseAccumulate<ResourceFailure>)
  private suspend fun landmarkAndSave(
    landmarker: HandLandmarker,
    image: GalleryImage,
    recognizers: List<GestureRecognizer<*>>,
    options: HandLandmarkerOptions,
  ) {
    recover(
      {
        galleryImageRepository.setScanStarted(image.uri)

        val candidate = imageLoader.load(image.uri, options.preferredImageDimension)
        val (gestureResults, duration) =
          measureTimedValue { landmarker.detectWithRecognizers(candidate, recognizers) }

        galleryImageRepository.setScanSuccessful(
          uri = image.uri,
          scanDuration = duration,
          scannedAt = Clock.System.now(),
          hands = gestureResults.toScoredHands(candidate, image.uri),
        )
      },
    ) { failure ->
      galleryImageRepository.setScanFailed(image.uri)
      raise(failure)
    }
  }

  private fun List<GestureResult>.toScoredHands(candidate: LandmarkCandidate, imageUri: String) =
    mapIndexedNotNull { index, result ->
      val hand = result.hand
      val score = result.score ?: return@mapIndexedNotNull null
      val displayHand =
        unrotateAndNormalize(
          hand,
          candidate,
        )
      val handFeatures = HandFeatures(displayHand)

      DetectedHand(
        id = -1,
        imageUri = imageUri,
        handIndex = index.toLong(),
        score = score,
        bboxAreaFraction = handFeatures.bboxAreaFraction.toDouble(),
        points = displayHand.points,
        uncertain = result.confidence < 1f,
        isUserRated = false,
      )
    }

  /**
   * Maps a hand's points from its rating frame to true image space: rotate by
   * +[LandmarkedImage.Hand.rotationDegrees] around the hand bbox center, then normalize to 0..1 by
   * the image dimensions for display/storage.
   */
  // TODO hand rotation is deprecated, so this function should be removed later. Consider dropping
  //  normalization too -- it's likely only used for developer mode landmark display.
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
