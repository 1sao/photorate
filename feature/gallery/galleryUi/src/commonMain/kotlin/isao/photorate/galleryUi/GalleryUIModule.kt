package isao.photorate.galleryUi

import isao.photorate.galleryComponent.GalleryComponentModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module(includes = [GalleryComponentModule::class])
@ComponentScan("isao.photorate.galleryUi")
class GalleryUIModule
