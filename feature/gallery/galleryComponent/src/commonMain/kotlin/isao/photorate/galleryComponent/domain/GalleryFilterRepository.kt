package isao.photorate.galleryComponent.domain

import isao.photorate.galleryComponent.db.SelectImagesWithScores
import isao.photorate.galleryComponent.db.SelectMatching
import isao.photorate.galleryComponent.db.SelectUncertainImagesWithScore
import kotlinx.coroutines.flow.Flow

interface GalleryFilterRepository {
  /** Detail view: matching hands with image info */
  fun selectMatching(): Flow<List<SelectMatching>>

  /** Grid view: distinct images with their distinct confident scores (for the rating stars). */
  fun selectImagesWithScores(): Flow<List<SelectImagesWithScores>>

  /**
   * Grid view: distinct images with only low-confidence (uncertain) hands, each with its best-guess
   * score for display.
   */
  fun selectUncertainImages(): Flow<List<SelectUncertainImagesWithScore>>
}
