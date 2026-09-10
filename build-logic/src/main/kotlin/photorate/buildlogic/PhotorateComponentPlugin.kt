package photorate.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Convention plugin for KMP data/domain modules (core, *Component).
 *
 * Modules that own SQLDelight schemas or need Skie ObjC export apply those plugins themselves:
 * sqldelight crashes the IDE sync when the plugin is applied without any `databases.create(...)`
 * (SQLDelight 2.3.2).
 */
class PhotorateComponentPlugin : Plugin<Project> {
  override fun apply(project: Project) =
    with(project) {
      applyKmpConventions(expectActualClasses = true)

      val sourceSets = extensions.getByType<KotlinMultiplatformExtension>().sourceSets
      sourceSets.getByName("commonMain").dependencies {
        implementation(libs.requireLibrary("koin-core"))
        implementation(libs.requireLibrary("koin-annotations"))
        implementation(libs.requireLibrary("coroutines-core"))
        implementation(libs.requireLibrary("arrow-core"))
      }
    }
}
