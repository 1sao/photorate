package photorate.buildlogic

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependencyBundle
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.koin.compiler.plugin.KoinGradleExtension

internal val Project.libs: VersionCatalog
  get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * Per-module KMP target knobs controlling optional module targets. Taken from root
 * gradle.properties (`photorate.<module>.<name>`, falling back to a global `photorate.<name>`).
 * Gradle ignores subproject gradle.properties files and module-script config is invisible at apply
 * time, so scoped root properties are the only eager per-module channel.
 *
 * TODO: consider dropping support for platforms requiring these knobs.
 */
private fun Project.targetKnob(name: String, default: Boolean): Boolean =
  providers
    .gradleProperty("photorate.${project.name}.$name")
    .orElse(providers.gradleProperty("photorate.$name"))
    .map(String::toBoolean)
    .getOrElse(default)

internal fun VersionCatalog.requireLibrary(name: String): MinimalExternalModuleDependency =
  findLibrary(name)
    .orElseThrow { IllegalStateException("Missing library '$name' in gradle/libs.versions.toml") }
    .get()

internal fun VersionCatalog.requireBundle(name: String): ExternalModuleDependencyBundle =
  findBundle(name)
    .orElseThrow { IllegalStateException("Missing bundle '$name' in gradle/libs.versions.toml") }
    .get()

/**
 * Common KMP module setup shared by the `photorate.component` and `photorate.ui` convention
 * plugins.
 */
internal fun Project.applyKmpConventions(expectActualClasses: Boolean) {
  // AGP's KMP android library plugin must be applied BEFORE kotlin.multiplatform:
  // ktfmt-gradle 0.27.0 snapshots the applied-plugin set when KMP applies and
  // silently skips src/androidMain otherwise.
  plugins.apply("com.android.kotlin.multiplatform.library")
  plugins.apply("org.jetbrains.kotlin.multiplatform")
  plugins.apply("io.insert-koin.compiler.plugin")

  extensions.configure<KoinGradleExtension>("koinCompiler") { compileSafety.set(true) }

  val includeIosX64 = targetKnob("iosX64", default = true)
  val includeJvm = targetKnob("jvm", default = false)

  extensions.configure<KotlinMultiplatformExtension>("kotlin") {
    jvmToolchain(libs.findVersion("jvmToolchain").get().requiredVersion.toInt())

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    if (expectActualClasses) {
      compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    }

    val iosTargets = mutableListOf<KotlinNativeTarget>()
    if (includeIosX64) iosTargets.add(iosX64())
    iosTargets.add(iosArm64())
    iosTargets.add(iosSimulatorArm64())
    iosTargets.forEach {
      // Configure lazily: a module may also declare `binaries.framework {}`
      // (e.g. :shared adds linkerOpts), and creating the binary eagerly here
      // would collide with it.
      it.binaries.configureEach { if (this is Framework) isStatic = true }
    }
    if (includeJvm) jvm()

    sourceSets.all {
      languageSettings.apply {
        optIn("kotlin.RequiresOptIn")
        optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
      }
    }
  }

  // AGP 9 registers the `android` block as a sub-extension on the kotlin
  // extension; configure the shared SDK boilerplate here, modules only set
  // their namespace.
  val kotlin = extensions.getByType<KotlinMultiplatformExtension>()
  (kotlin as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryExtension>(
    "android",
  ) {
    compileSdk = libs.findVersion("compileSdk").get().requiredVersion.toInt()
    minSdk = libs.findVersion("minSdk").get().requiredVersion.toInt()
    androidResources.enable = true
  }
}
