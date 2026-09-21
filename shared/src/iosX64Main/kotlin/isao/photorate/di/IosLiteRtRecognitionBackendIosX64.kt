package isao.photorate.di

import isao.photorate.imageRecognition.RecognitionBackend

/** iosX64 stub: kmplitert publishes no iosX64 klib, so the LiteRT pipeline cannot load here. */
actual class IosRecognitionBackendFactory actual constructor() {
  actual fun create(): RecognitionBackend =
    error("LiteRT recognition backend is not available on iosX64")
}
