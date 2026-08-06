package isao.photorate.galleryUi

import isao.photorate.db.SelectUncertainImagesWithScore
import isao.photorate.galleryRepository.ConfigRepository
import isao.photorate.galleryRepository.GalleryFilterRepository
import isao.photorate.galleryRepository.GalleryImageRepository
import isao.photorate.galleryRepository.GallerySorting
import isao.photorate.galleryRepository.GalleryStatusCounts
import isao.photorate.galleryUi.GalleryUiState.ImageSorting
import isao.photorate.galleryUi.GalleryUiState.ImagesState
import isao.photorate.galleryUi.GalleryUiState.PermissionState
import isao.photorate.inference.classify.Score
import isao.photorate.photosComponent.classify.LandmarkPendingImagesUseCase
import isao.photorate.photosComponent.gallery.SetUserScoreUseCase
import isao.photorate.photosComponent.populateGallery.PopulateGalleryUseCase
import isao.photorate.photosComponent.search.PopulateImageEmbeddingsUseCase
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Factory

/**
 * Owns the gallery grid state ([GalleryUiState]) and its lifecycle for the
 * gallery screen: scan status counts, confident detections, the uncertain
 * review tier and the launch-time / trigger-driven rescan pipeline. Extracted
 * from [GalleryViewModel] so the gallery state and the search state evolve
 * (and recompose) independently.
 *
 * The delegate is a cold-flow producer: [uiState] combines the underlying
 * repository flows and is collected (via [GalleryViewModel]) only while the
 * screen is visible. One-shot actions ([rescan], [acceptUncertain],
 * [deleteUncertain]) are suspend and serialized here; the ViewModel launches
 * them on its own scope.
 */
interface GalleryDelegate {
    /** Cold flow of the gallery grid state, derived from the repository flows. */
    val uiState: Flow<GalleryUiState>

    /** Re-runs gallery population + landmarking + embedding population. */
    suspend fun rescan()

    /** Accepts an uncertain best-guess as a confident user rating. */
    suspend fun acceptUncertain(uri: String, score: Int)

    /** Rejects an uncertain guess, marking the image as no-hand. */
    suspend fun deleteUncertain(uri: String)
}

@OptIn(FlowPreview::class)
@Factory
class DefaultGalleryDelegate(
    private val populateGallery: PopulateGalleryUseCase,
    private val galleryImageRepository: GalleryImageRepository,
    private val landmarkPendingImages: LandmarkPendingImagesUseCase,
    private val galleryFilterRepository: GalleryFilterRepository,
    private val populateImageEmbeddings: PopulateImageEmbeddingsUseCase,
    private val setUserScore: SetUserScoreUseCase,
    private val configRepository: ConfigRepository,
) : GalleryDelegate {

    /**
     * The gallery state is the live combination of the repository flows:
     * confident detections, the uncertain review tier, the sampled scan status
     * counts and the user's sorting preference. Only the piece that changed
     * re-emits, so e.g. a status tick keeps the detections list reference
     * stable.
     */
    override val uiState: Flow<GalleryUiState> = combine(
        galleryFilterRepository.selectImagesWithScores(),
        galleryFilterRepository.selectUncertainImages(),
        galleryImageRepository.observeStatusCounts().sample(.1.seconds),
        configRepository.getConfig(),
    ) { images, uncertain, status, config ->
        GalleryUiState(
            permissionState = PermissionState.Denied,
            imagesState = ImagesState(
                status = status,
                detections = images.map { row ->
                    GalleryImageItem(
                        uri = row.uri,
                        scores = parseScores(row.scores),
                        scannedAt = row.scannedAt,
                    )
                },
                uncertainDetections = uncertain,
                sorting = config.sorting.toImageSorting(),
            ),
        )
    }

    /**
     * Re-runs gallery population + landmarking + embedding population.
     * Serialized with a mutex: rescans share the singleton landmarker and
     * overlapping scans caused native crashes (see LandmarkPendingImagesUseCase),
     * so a scan that's already running is never overlapped — callers queue
     * instead.
     */
    private val rescanLock = Mutex()

    override suspend fun rescan() {
        rescanLock.withLock {
            populateGallery()
            landmarkPendingImages()
            // Precompute CLIP embeddings for the newly detected hands so search
            // ranks stored vectors instead of re-embedding the gallery.
            populateImageEmbeddings()
        }
    }

    // --- Uncertain best-guess review ---

    /**
     * Accepts an uncertain best-guess: saves [score] as a confident user
     * rating (a user-rated hand always shows in the main grid), so the image
     * leaves the uncertain section and joins the confident gallery.
     */
    override suspend fun acceptUncertain(uri: String, score: Int) {
        val enumScore = Score.entries.firstOrNull { it.score == score } ?: return
        runCatching { setUserScore(uri, enumScore) }
    }

    /**
     * Rejects an uncertain guess: atomically drops the image's detections and
     * embedding and marks it DONE without hands — the same treatment as the
     * majority of scanned images, so a rescan won't re-detect it.
     */
    override suspend fun deleteUncertain(uri: String) {
        runCatching { galleryImageRepository.markNoHand(uri) }
    }
}

data class GalleryUiState(val permissionState: PermissionState = PermissionState.Denied, val imagesState: ImagesState = ImagesState()) {
    sealed interface PermissionState {
        data object Granted : PermissionState
        data object GrantedPartially : PermissionState
        data object Denied : PermissionState
    }

    data class ImagesState(
        val status: GalleryStatusCounts = GalleryStatusCounts(emptyMap()),
        val detections: List<GalleryImageItem> = emptyList(),
        /** Images whose hands are all low-confidence guesses (uncertain tier). */
        val uncertainDetections: List<SelectUncertainImagesWithScore> = emptyList(),
        val sorting: ImageSorting = ImageSorting.Date(isAscending = false),
    )

    sealed interface ImageSorting {
        val isAscending: Boolean

        data class Date(override val isAscending: Boolean) : ImageSorting
        data class Score(override val isAscending: Boolean) : ImageSorting
    }
}

/** One grid cell: the image plus the distinct scores its hands carry. */
data class GalleryImageItem(
    val uri: String,
    val scores: List<Score>,
    /** Scan timestamp (epoch millis); drives the date sections of the index scrollbar. */
    val scannedAt: Long? = null,
)

private fun GallerySorting.toImageSorting(): ImageSorting = when (this) {
    GallerySorting.SCORE -> ImageSorting.Score(isAscending = false)
    GallerySorting.DATE -> ImageSorting.Date(isAscending = false)
}

/** Parses the comma-separated score list from the grid query (e.g. "3.0,5.0"). */
private fun parseScores(aggregated: String?): List<Score> = aggregated
    ?.split(',')
    .orEmpty()
    .mapNotNull { token ->
        val value = token.trim().toDoubleOrNull()?.roundToInt() ?: return@mapNotNull null
        Score.entries.firstOrNull { it.score == value }
    }
    .distinct()
    .sortedBy { it.score }
