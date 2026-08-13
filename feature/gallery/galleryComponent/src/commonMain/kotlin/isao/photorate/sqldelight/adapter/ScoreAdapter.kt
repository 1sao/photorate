package isao.photorate.sqldelight.adapter

import app.cash.sqldelight.ColumnAdapter
import isao.photorate.imageRecognition.classify.Score

object ScoreAdapter : ColumnAdapter<Score, Double> { // TODO switch to long?
  override fun decode(databaseValue: Double): Score =
    Score.entries.first { it.score.toDouble() == databaseValue }

  override fun encode(value: Score): Double = value.score.toDouble()
}
