package photorate.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Convention plugin for KMP UI modules (coreUi, *Ui). Applies the shared KMP + Koin compiler +
 * Compose compiler setup and the standard compose/lifecycle/koin dependency stack shared by every
 * UI module.
 */
class PhotorateUiPlugin : Plugin<Project> {
  override fun apply(project: Project) =
    with(project) {
      applyKmpConventions(expectActualClasses = false)
      plugins.apply("org.jetbrains.kotlin.plugin.compose")

      extensions.configure<ComposeCompilerGradlePluginExtension> {
        stabilityConfigurationFiles.add(
          rootProject.layout.projectDirectory.file("app/compose_stability.conf"),
        )
      }

      val sourceSets = extensions.getByType<KotlinMultiplatformExtension>().sourceSets
      sourceSets.getByName("commonMain").dependencies {
        implementation(libs.requireLibrary("koin-core"))
        implementation(libs.requireLibrary("koin-annotations"))
        implementation(libs.requireLibrary("koin-viewmodel"))
        implementation(libs.requireLibrary("coroutines-core"))
        implementation(libs.requireLibrary("androidx-lifecycle-viewmodel-compose"))
      }

      sourceSets.getByName("androidMain").dependencies {
        libs.requireBundle("app-ui").forEach { implementation(it) }
        implementation(libs.requireLibrary("compose-ui"))
        implementation(libs.requireLibrary("compose-ui-tooling-preview"))
        implementation(libs.requireLibrary("compose-foundation"))
        implementation(libs.requireLibrary("compose-material3"))
        implementation(libs.requireLibrary("compose-material-icons-core"))
        implementation(libs.requireLibrary("androidx-lifecycle-viewmodel"))
        implementation(libs.requireLibrary("androidx-lifecycle-compose"))
        implementation(libs.requireLibrary("androidx-core"))
        implementation(libs.requireLibrary("compose-animation"))
        implementation(libs.requireLibrary("compose-material3-window-size"))
        implementation(libs.requireLibrary("navigation3-ui"))
        implementation(libs.requireLibrary("accompanist-permissions"))
        implementation(libs.requireLibrary("coil-compose"))
        implementation(libs.requireLibrary("coil-network-okhttp"))
        implementation(libs.requireLibrary("koin-androidx-compose"))
      }
    }
}
