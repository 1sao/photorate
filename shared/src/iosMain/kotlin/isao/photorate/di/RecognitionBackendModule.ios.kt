package isao.photorate.di

import isao.photorate.imageRecognition.RecognitionBackend
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module
actual class RecognitionBackendModule {
  @Single
  fun provideRecognitionBackend(factory: IosRecognitionBackendFactory): RecognitionBackend =
    factory.create()
}
