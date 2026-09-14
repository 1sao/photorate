package isao.photorate.imageRecognition.landmark

/** Platform image a hand-landmark model consumes. */
expect class LandmarkCandidate

/** Width in pixels (used to normalize landmark points to 0..1 for storage). */
expect val LandmarkCandidate.widthPx: Int

/** Height in pixels (used to normalize landmark points to 0..1 for storage). */
expect val LandmarkCandidate.heightPx: Int
