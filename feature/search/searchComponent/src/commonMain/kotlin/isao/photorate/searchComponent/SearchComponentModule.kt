package isao.photorate.searchComponent

import isao.photorate.core.CoreModule
import isao.photorate.galleryComponent.GalleryComponentModule
import isao.photorate.search.db.PhotoRateDb
import isao.photorate.search.db.SearchHistoryQueries
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Provided

@Module(includes = [GalleryComponentModule::class, CoreModule::class])
@ComponentScan("isao.photorate.searchComponent")
class SearchComponentModule {
  @Factory
  fun provideSearchHistoryQueries(@Provided db: PhotoRateDb): SearchHistoryQueries =
    db.searchHistoryQueries
}
