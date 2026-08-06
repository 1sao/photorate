package isao.photorate.photosComponent

import isao.photorate.photosComponent.di.DatabaseModule
import isao.photorate.photosComponent.di.LogModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

/**
 * Scans all components that live in this module. The Koin compiler plugin
 * resolves each @Module class's scan independently, so all packages of this
 * Gradle module are scanned from this single module class (otherwise KOIN-D001
 * would report components of one scan as missing from another).
 *
 * `includes` makes the plugin compose this module's graph with the di modules
 * (Logger, SqlDriver, PhotoRateDb) so the scanned components' dependencies
 * resolve at leaf-module compile time instead of being deferred.
 */
@Module(includes = [LogModule::class, DatabaseModule::class])
@ComponentScan(
    "isao.photorate.photosComponent",
    "isao.photorate.galleryRepository",
)
class PhotosComponentModule
