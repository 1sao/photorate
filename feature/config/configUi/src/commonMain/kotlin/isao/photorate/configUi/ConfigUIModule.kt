package isao.photorate.configUi

import isao.photorate.config.ConfigModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module(includes = [ConfigModule::class])
@ComponentScan("isao.photorate.configUi")
class ConfigUIModule
