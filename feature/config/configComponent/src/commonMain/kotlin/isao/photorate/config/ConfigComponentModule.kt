package isao.photorate.config

import isao.photorate.config.db.ConfigQueries
import isao.photorate.config.db.PhotoRateDb
import isao.photorate.core.CoreModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Provided

@Module(includes = [CoreModule::class])
@ComponentScan("isao.photorate.config")
class ConfigComponentModule {
  @Factory fun provideConfigQueries(@Provided db: PhotoRateDb): ConfigQueries = db.configQueries
}
