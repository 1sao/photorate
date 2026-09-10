package isao.photorate.core

import isao.photorate.core.di.LogModule
import org.koin.core.annotation.Module

/**
 * Aggregates the cross-cutting bindings this module provides (the logger, the JSON codec). Feature
 * modules include this module so their components can resolve `Logger`/`Json` at leaf-module
 * compile time (KOIN-D001), the same way they consume the driver libraries and adapters.
 *
 * Deliberately scan-less: `@ComponentScan` over an empty component package would generate no hints
 * and mark the module "incomplete" in consumers' compilations, silently disabling full-graph
 * (KOIN-D001) validation there. Consumers must also have `core` on their compile classpath (exposed
 * `api` by feature modules) so the Koin compiler plugin can resolve the module class itself.
 */
@Module(includes = [LogModule::class]) class CoreModule
