package isao.photorate.homeUi

import isao.photorate.configUi.ConfigUIModule
import isao.photorate.galleryUi.GalleryUIModule
import isao.photorate.searchUi.SearchUIModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module(includes = [GalleryUIModule::class, SearchUIModule::class, ConfigUIModule::class])
@ComponentScan("isao.photorate.homeUi")
class HomeUIModule
