package isao.photorate.galleryUi

import isao.photorate.photosComponent.PhotosComponentModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

/**
 * Scans the GalleryViewModel ([org.koin.core.annotation.KoinViewModel]).
 *
 * `includes` composes this module's graph with [PhotosComponentModule] so the
 * ViewModel's dependencies (use cases, repositories, the provider-agnostic
 * landmark scan) resolve at leaf-module compile time — the compiler plugin
 * validates each Gradle module's `@Module` classes against its own graph
 * (KOIN-D001), and consumer-provided bindings are never visible to a leaf
 * module. The active hand-landmark model (MediaPipe / ONNX) is chosen by
 * shared's PlatformModule (the [isao.photorate.photosComponent.classify.LandmarkerFactoryProvider]
 * seam), not here.
 */
@Module(includes = [PhotosComponentModule::class])
@ComponentScan("isao.photorate.galleryUi", "isao.photorate.configUi")
class PhotosUIModule
