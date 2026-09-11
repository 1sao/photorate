package isao.photorate.core

import isao.photorate.core.di.LogModule
import org.koin.core.annotation.Module

@Module(includes = [LogModule::class]) class CoreModule
