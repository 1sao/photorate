package isao.photorate.imageRecognition.mediapipe

import isao.photorate.imageRecognition.landmark.HandLandmarker
import isao.photorate.imageRecognition.landmark.HandLandmarkerFactory
import isao.photorate.imageRecognition.landmark.HandLandmarkerOptions
import isao.photorate.imageRecognition.landmark.LandmarkCandidate
import isao.photorate.imageRecognition.landmark.LandmarkedImage
import kotlin.time.measureTime
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.Foundation.NSBundle
import platform.Foundation.NSLog
import swiftPMImport.PhotoRate.shared.MPPBaseOptions
import swiftPMImport.PhotoRate.shared.MPPDelegate
import swiftPMImport.PhotoRate.shared.MPPHandLandmarker
import swiftPMImport.PhotoRate.shared.MPPHandLandmarkerOptions
import swiftPMImport.PhotoRate.shared.MPPHandLandmarkerResult
import swiftPMImport.PhotoRate.shared.MPPImage
import swiftPMImport.PhotoRate.shared.MPPNormalizedLandmark
import swiftPMImport.PhotoRate.shared.MPPRunningMode

/**
 * iOS [HandLandmarkerFactory] backed by MediaPipe Tasks (SwiftTasksVision). The common contract's
 * [LandmarkCandidate] is a UIImage on iOS; MediaPipe wants an MPPImage, so detect() wraps it here
 * and scales the normalized landmarks back to image points (see the Android counterpart for why).
 */
@OptIn(ExperimentalForeignApi::class)
class IosMediaPipeHandLandmarkerFactory : HandLandmarkerFactory {
  override fun createFromOptions(options: HandLandmarkerOptions): HandLandmarker {
    val modelPath =
      NSBundle.mainBundle.pathForResource(
        "hand_landmarker",
        ofType = "task",
      )!!
    val mppOptions =
      MPPHandLandmarkerOptions().apply {
        baseOptions =
          MPPBaseOptions().apply {
            modelAssetPath = modelPath
            delegate = MPPDelegate.MPPDelegateGPU
          }
        runningMode = MPPRunningMode.MPPRunningModeImage
        numHands = options.maxNumHands.toLong()
        minHandDetectionConfidence = options.minHandDetectionConfidence
        minHandPresenceConfidence = options.minHandPresenceConfidence
      }
    val landmarker: MPPHandLandmarker
    measureTime {
      landmarker =
        MPPHandLandmarker(
          mppOptions,
          null,
        )
    }
      .also { NSLog("IosMediaPipeHandLandmarkerFactory initialized in $it ms") }
    return IosMediaPipeHandLandmarker(landmarker)
  }
}

@OptIn(ExperimentalForeignApi::class)
class IosMediaPipeHandLandmarker internal constructor(private val landmarker: MPPHandLandmarker) :
  HandLandmarker {
  override fun detect(candidate: LandmarkCandidate): LandmarkedImage {
    val result: MPPHandLandmarkerResult
    val detectedIn = measureTime {
      result =
        landmarker.detectImage(
          MPPImage(
            candidate,
            null,
          ),
          null,
        )!!
    }
    val imageWidth = candidate.size.useContents { width.toFloat() }
    val imageHeight = candidate.size.useContents { height.toFloat() }
    return LandmarkedImage(
      hands =
        result.landmarks().map { landmarks ->
          val handDetections = landmarks as List<MPPNormalizedLandmark>
          LandmarkedImage.Hand(
            points =
              handDetections.map {
                LandmarkedImage.Point(
                  x = it.x() * imageWidth,
                  y = it.y() * imageHeight,
                  z = it.z(),
                )
              }
          )
        },
      detectedInMs = detectedIn.inWholeMilliseconds,
    )
  }

  override fun close() {
    // MPPHandLandmarker has no close() in the
    // SwiftTasksVision interop.
  }
}
