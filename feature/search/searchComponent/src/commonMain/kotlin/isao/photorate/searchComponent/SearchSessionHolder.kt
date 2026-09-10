package isao.photorate.searchComponent

import isao.photorate.core.SessionHolder
import isao.photorate.imageRecognition.search.AppClipSearch
import isao.photorate.imageRecognition.search.AppClipSearchFactory
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single
class SearchSessionHolder(
  @Provided private val searchFactory: AppClipSearchFactory,
) :
  SessionHolder<AppClipSearch>(
    factory = { searchFactory.createFromOptions(AppClipSearchFactory.Options()) },
  )
