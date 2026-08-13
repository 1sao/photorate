package isao.photorate.searchUi

import isao.photorate.searchComponent.SearchComponentModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module(includes = [SearchComponentModule::class])
@ComponentScan("isao.photorate.searchUi")
class SearchUIModule
