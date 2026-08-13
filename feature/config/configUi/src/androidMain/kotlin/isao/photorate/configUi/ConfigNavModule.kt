package isao.photorate.configUi

import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

@OptIn(KoinExperimentalAPI::class)
val ConfigNavModule = module { navigation<ConfigRoute> { ConfigScreen() } }
