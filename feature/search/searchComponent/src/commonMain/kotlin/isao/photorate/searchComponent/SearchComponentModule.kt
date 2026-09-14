package isao.photorate.searchComponent

import isao.photorate.core.CoreModule
import isao.photorate.galleryComponent.GalleryComponentModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module(includes = [GalleryComponentModule::class, CoreModule::class])
@ComponentScan("isao.photorate.searchComponent")
class SearchComponentModule
