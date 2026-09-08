package photorate.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Convention plugin for the `:shared` umbrella module. Applies the full KMP stack (serialization,
 * skie, compose compiler + compose multiplatform) and the shared dependency sets; the module keeps
 * its iOS-specific framework/dylib wiring.
 */
class PhotorateSharedPlugin : Plugin<Project> {
  override fun apply(project: Project) =
    with(project) {
      applyKmpConventions(expectActualClasses = true)
      plugins.apply("org.jetbrains.kotlin.plugin.serialization")
      plugins.apply("co.touchlab.skie")
      plugins.apply("org.jetbrains.kotlin.plugin.compose")
      plugins.apply("org.jetbrains.compose")

      val sourceSets = extensions.getByType<KotlinMultiplatformExtension>().sourceSets
      sourceSets.named("commonMain").configure {
        dependencies {
          implementation(libs.requireLibrary("koin-core"))
          implementation(libs.requireLibrary("koin-annotations"))
          implementation(libs.requireLibrary("koin-viewmodel"))
          implementation(libs.requireLibrary("koin-compose-viewmodel"))
          implementation(libs.requireLibrary("coroutines-core"))
          implementation(libs.requireLibrary("sqlDelight-coroutinesExt"))
          implementation(libs.requireLibrary("kotlinx-dateTime"))
          implementation(libs.requireLibrary("touchlab-skie-annotations"))
          api(libs.requireLibrary("touchlab-kermit"))
          implementation(libs.requireLibrary("arrow-core"))
          implementation(libs.requireLibrary("arrow-fx-coroutines"))
          implementation(libs.requireLibrary("androidx-lifecycle-viewmodel-compose"))
          implementation(project(":feature:gallery:galleryComponent"))
          implementation(project(":feature:config:configComponent"))
          implementation(project(":feature:search:searchComponent"))
          implementation(project(":core"))
          implementation(project(":feature:home:homeUi"))
          implementation(project(":feature:gallery:galleryUi"))
          implementation(project(":feature:search:searchUi"))
          implementation(project(":feature:config:configUi"))
          implementation(project(":feature:imageRecognition:imageRecognitionComponentApi"))
        }
      }
      sourceSets.named("commonTest").configure {
        dependencies { libs.requireBundle("shared-commonTest").forEach { implementation(it) } }
      }
      // androidHostTest is created by the module's withHostTestBuilder (after
      // this plugin applies); configureEach covers the future element.
      sourceSets.configureEach {
        if (name == "androidHostTest") {
          dependencies { libs.requireBundle("shared-androidTest").forEach { implementation(it) } }
        }
      }
      sourceSets.named("androidMain").configure {
        dependencies {
          implementation(libs.requireLibrary("android-worker"))
          implementation(libs.requireLibrary("koin-worker"))
        }
      }
    }
}
