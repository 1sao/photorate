package isao.photorate.di

import isao.photorate.imageRecognition.RecognitionBackend

expect class IosRecognitionBackendFactory() {
  fun create(): RecognitionBackend
}
