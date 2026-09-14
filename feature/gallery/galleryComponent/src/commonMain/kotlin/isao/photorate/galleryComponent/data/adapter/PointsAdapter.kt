package isao.photorate.galleryComponent.data.adapter

import app.cash.sqldelight.ColumnAdapter
import isao.photorate.imageRecognition.landmark.LandmarkedImage
import kotlinx.serialization.json.Json

object PointsAdapter : ColumnAdapter<List<LandmarkedImage.Point>, String> {
  override fun decode(databaseValue: String): List<LandmarkedImage.Point> =
    Json.decodeFromString(databaseValue)

  override fun encode(value: List<LandmarkedImage.Point>): String = Json.encodeToString(value)
}
