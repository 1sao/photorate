package isao.photorate.galleryComponent

import isao.photorate.config.ConfigModule
import isao.photorate.core.CoreModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module(includes = [CoreModule::class, ConfigModule::class])
@ComponentScan("isao.photorate.galleryComponent")
class GalleryComponentModule
