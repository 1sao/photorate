package isao.photorate.homeUi

import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

@OptIn(KoinExperimentalAPI::class)
val HomeNavModule = module {
  single { NavDispatcher() }
  navigation<HomeRoute> { HomeScreen() }
}
