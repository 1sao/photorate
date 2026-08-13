package isao.photorate

import isao.photorate.imageRecognition.classify.HandLandmarkerFactory
import isao.photorate.imageRecognition.classify.LandmarkModel
import isao.photorate.imageRecognition.classify.LandmarkerFactoryProvider

/**
 * Concrete [LandmarkerFactoryProvider]: a fixed default model and one factory per [LandmarkModel].
 * The platform modules build it with their own factory implementations (LiteRT/MediaPipe on
 * Android, MediaPipe on iOS).
 */
class DefaultLandmarkerFactoryProvider(
  override val defaultModel: LandmarkModel,
  private val factories: Map<LandmarkModel, HandLandmarkerFactory>,
) : LandmarkerFactoryProvider {
  override fun factoryFor(model: LandmarkModel): HandLandmarkerFactory =
    requireNotNull(factories[model]) { "No landmarker factory registered for $model" }
}
