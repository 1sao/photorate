package isao.photorate.galleryUi

import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

@OptIn(KoinExperimentalAPI::class)
val GalleryNavModule = module {
  navigation<ImageDetailsRoute> { route -> ImageDetailsScreen(uri = route.uri) }
}
