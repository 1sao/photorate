package isao.photorate.sqldelight.adapter

import app.cash.sqldelight.ColumnAdapter
import isao.photorate.photosComponent.classify.LandmarkedImage
import kotlinx.serialization.json.Json

object PointsAdapter : ColumnAdapter<List<LandmarkedImage.Point>, String> {
    override fun decode(databaseValue: String): List<LandmarkedImage.Point> {
        // return
        // if (databaseValue.isEmpty()) {
        //     emptyList()
        // } else {
        return Json.decodeFromString(databaseValue)
        // }
    }

    override fun encode(value: List<LandmarkedImage.Point>): String = Json.encodeToString(value)
}
