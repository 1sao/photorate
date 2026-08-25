package isao.photorate.imageRecognition.classify

/**
 * The thumb-rating score (1–5) a hand communicates. Produced by gesture recognizers; stored per
 * detected hand.
 */
enum class Score(val score: Int) {
  ONE(1),
  TWO(2),
  THREE(3),
  FOUR(4),
  FIVE(5),
}
