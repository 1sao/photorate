package isao.photorate.imageRecognition

import isao.photorate.imageRecognition.landmark.HandLandmarkerFactory
import isao.photorate.imageRecognition.recognizer.GestureRecognizerProvider
import isao.photorate.imageRecognition.search.AppClipSearchFactory

interface RecognitionBackend {
  val handLandmarkerFactory: HandLandmarkerFactory
  val gestureRecognizerProvider: GestureRecognizerProvider
  val appClipSearchFactory: AppClipSearchFactory
}
