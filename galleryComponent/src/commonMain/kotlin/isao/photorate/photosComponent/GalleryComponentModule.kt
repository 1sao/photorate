package isao.photorate.galleryComponent

import isao.photorate.config.ConfigModule
import isao.photorate.core.CoreModule
import isao.photorate.galleryComponent.di.DatabaseModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

/**
 * Scans all components that live in this module. The Koin compiler plugin
 * resolves each @Module class's scan independently, so all packages of this
 * Gradle module are scanned from this single module class (otherwise KOIN-D001
 * would report components of one scan as missing from another).
 *
 * `includes` makes the plugin compose this module's graph with the di modules
 * (the cross-cutting logger from core, the config repositories, SqlDriver,
 * PhotoRateDb) so the scanned components' dependencies resolve at leaf-module
 * compile time instead of being deferred.
 */
@Module(includes = [CoreModule::class, ConfigModule::class, DatabaseModule::class])
@ComponentScan(
    "isao.photorate.galleryComponent",
    "isao.photorate.galleryRepository",
)
class GalleryComponentModule
