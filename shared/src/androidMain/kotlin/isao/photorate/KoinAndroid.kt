package isao.photorate

import isao.photorate.galleryOld.AndroidGalleryDataSource
import isao.photorate.galleryOld.GalleryDataSource
import isao.photorate.photosComponent.classify.DefaultLandmarkerFactoryProvider
import isao.photorate.photosComponent.classify.LandmarkModel
import isao.photorate.photosComponent.classify.LandmarkerFactoryProvider
import isao.photorate.photosComponent.search.AndroidAppClipSearchFactory
import isao.photorate.photosComponent.search.AppClipSearchFactory
import isao.photorate.photosMediaPipe.AndroidMediaPipeHandLandmarkerFactory
import isao.photorate.photosOnnx.AndroidOnnxHandLandmarkerFactory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.scope.Scope

object AndroidAppInfo : AppInfo {
    override val appId: String = "isao.photorate"
}

@Module
actual class PlatformModule {

    @Single
    actual fun provideAppInfo(): AppInfo = AndroidAppInfo

    /**
     * The active Android hand-landmark model is ONNX (the verified RTMPose
     * pipeline). Switch [LandmarkModel.ONNX] to [LandmarkModel.MEDIAPIPE] to
     * run the MediaPipe pipeline instead.
     */
    @Single
    actual fun provideLandmarkerFactoryProvider(scope: Scope): LandmarkerFactoryProvider = DefaultLandmarkerFactoryProvider(
        defaultModel = LandmarkModel.ONNX,
        factories = mapOf(
            LandmarkModel.ONNX to AndroidOnnxHandLandmarkerFactory(scope.get()),
            LandmarkModel.MEDIAPIPE to AndroidMediaPipeHandLandmarkerFactory(scope.get()),
        ),
    )

    @Single
    actual fun provideAppClipSearchFactory(scope: Scope): AppClipSearchFactory = AndroidAppClipSearchFactory(scope.get())

    @Single
    actual fun provideGalleryDataSource(scope: Scope): GalleryDataSource = AndroidGalleryDataSource(scope.get())
}
