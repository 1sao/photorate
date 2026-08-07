package isao.photorate.galleryComponent.classify

import isao.photorate.inference.classify.LandmarkedImage.Hand

data class HandFeatures(
  val bboxMinX: Float,
  val bboxMinY: Float,
  val bboxMaxX: Float,
  val bboxMaxY: Float,
  val bboxAreaFraction: Float,
  val centroidX: Float,
  val centroidY: Float,
)

object HandFeatureExtractor {
  fun extract(hand: Hand): HandFeatures {
    val points = hand.points
    val minX = points.minOf { it.x }
    val minY = points.minOf { it.y }
    val maxX = points.maxOf { it.x }
    val maxY = points.maxOf { it.y }
    val areaFraction = (maxX - minX) * (maxY - minY)
    val centroidX = points.map { it.x }.average().toFloat()
    val centroidY = points.map { it.y }.average().toFloat()
    return HandFeatures(
      minX,
      minY,
      maxX,
      maxY,
      areaFraction,
      centroidX,
      centroidY,
    )
  }

  // fun extract(detection: LandmarkDetection): HandFeatures {
  //     detection.landmarked.hands
  //     val points = hand.points
  //     val minX = points.minOf { it.x }
  //     val minY = points.minOf { it.y }
  //     val maxX = points.maxOf { it.x }
  //     val maxY = points.maxOf { it.y }
  //     val areaFraction = (maxX - minX) * (maxY - minY)
  //     val centroidX = points.map { it.x }.average().toFloat()
  //     val centroidY = points.map { it.y }.average().toFloat()
  //     return HandFeatures(minX, minY, maxX, maxY, areaFraction, centroidX,
  // centroidY)
  // }
}
