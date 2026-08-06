package isao.photorate

import isao.photorate.galleryOld.AndroidGalleryDataSource
import isao.photorate.galleryOld.GalleryDataSource
import isao.photorate.photosComponent.classify.DefaultLandmarkerFactoryProvider
import isao.photorate.photosComponent.classify.LandmarkModel
import isao.photorate.photosComponent.classify.LandmarkerFactoryProvider
import isao.photorate.photosComponent.search.AppClipSearchFactory
import isao.photorate.photoslitert.AndroidLiteRtAppClipSearchFactory
import isao.photorate.photoslitert.AndroidLiteRtHandLandmarkerFactory
import isao.photorate.photosMediaPipe.AndroidMediaPipeHandLandmarkerFactory
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
     * The active Android hand-landmark model is LiteRT (the CompiledModel
     * pipeline running the RTMDet + RTMPose conversions on GPU, CPU fallback).
     * The ONNX provider (photosOnnx) is unplugged for now. Switch
     * [LandmarkModel.LITERT] to [LandmarkModel.MEDIAPIPE] to run the MediaPipe
     * pipeline instead.
     */
    @Single
    actual fun provideLandmarkerFactoryProvider(scope: Scope): LandmarkerFactoryProvider = DefaultLandmarkerFactoryProvider(
        defaultModel = LandmarkModel.LITERT,
        factories = mapOf(
            LandmarkModel.LITERT to AndroidLiteRtHandLandmarkerFactory(scope.get()),
            LandmarkModel.MEDIAPIPE to AndroidMediaPipeHandLandmarkerFactory(scope.get()),
        ),
    )

    @Single
    actual fun provideAppClipSearchFactory(scope: Scope): AppClipSearchFactory = AndroidLiteRtAppClipSearchFactory(scope.get())

    @Single
    actual fun provideGalleryDataSource(scope: Scope): GalleryDataSource = AndroidGalleryDataSource(scope.get())
}
