package isao.photorate.photosComponent.classify

import isao.photorate.galleryRepository.FeatureFlagRepository
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Factory

/**
 * Resolves the [LandmarkRater] a scan should use: the real one by default, or
 * the dev-mode [DebugLandmarkRater] (best-guess wrapper) when Developer Mode
 * is enabled. The flag is read once per scan, so toggling it takes effect on
 * the next rescan (see the purge-and-rescan action in Settings).
 */
@Factory
class LandmarkRaterProvider(private val realRater: LandmarkRater, private val featureFlagRepository: FeatureFlagRepository) {
    suspend fun raterForScan(): LandmarkRater = if (featureFlagRepository.devModeEnabled().first()) {
        DebugLandmarkRater(realRater)
    } else {
        realRater
    }
}
