package isao.photorate.configUi

import isao.photorate.config.ConfigComponentModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module(includes = [ConfigComponentModule::class])
@ComponentScan("isao.photorate.configUi")
class ConfigUIModule
