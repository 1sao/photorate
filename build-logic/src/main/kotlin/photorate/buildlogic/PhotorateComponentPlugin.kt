package photorate.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Convention plugin for KMP data/domain modules (core, *Component). Applies the shared KMP + Koin
 * compiler setup and the common koin-core/koin-annotations/coroutines-core dependency trio.
 */
class PhotorateComponentPlugin : Plugin<Project> {
  override fun apply(project: Project) =
    with(project) {
      applyKmpConventions(expectActualClasses = true)
      plugins.apply("app.cash.sqldelight")
      plugins.apply("co.touchlab.skie")

      val sourceSets = extensions.getByType<KotlinMultiplatformExtension>().sourceSets
      sourceSets.getByName("commonMain").dependencies {
        implementation(libs.requireLibrary("koin-core"))
        implementation(libs.requireLibrary("koin-annotations"))
        implementation(libs.requireLibrary("coroutines-core"))
      }
    }
}
