import com.ncorti.ktfmt.gradle.KtfmtExtension
import com.ncorti.ktfmt.gradle.TrailingCommaManagementStrategy
import com.ncorti.ktfmt.gradle.tasks.KtfmtFormatTask

plugins {
  alias(libs.plugins.ktfmt) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.sqlDelight) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.skie) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.android.lint) apply false
  id("com.google.gms.google-services") version "4.5.0" apply false
  id("com.google.firebase.crashlytics") version "3.0.7" apply false
}

apply(plugin = rootProject.libs.plugins.ktfmt.get().pluginId)

fun KtfmtExtension.applyProjectStyle() {
  blockIndent.set(2)
  continuationIndent.set(2)
  maxWidth.set(100)
  removeUnusedImports.set(false)
  trailingCommaManagementStrategy.set(TrailingCommaManagementStrategy.ONLY_ADD)
}

configure<KtfmtExtension> { applyProjectStyle() }

subprojects {
  if (!file("build.gradle.kts").exists()) {
    // Group directories (feature, component, etc.) are virtual projects with no
    // build file or tasks; skip convention wiring so `check`/ktfmt lookups don't fail.
    return@subprojects
  }

  apply(plugin = rootProject.libs.plugins.ktfmt.get().pluginId)

  // ktfmt-gradle 0.27.0 skips src/androidMain sources unless
  // com.android.kotlin.multiplatform.library
  // is applied BEFORE kotlin.multiplatform (it snapshots the applied-plugin set when KMP applies).
  // New KMP modules with Android sources must keep that plugins {} order or androidMain silently
  // loses ktfmt coverage without failing any check.
  configure<KtfmtExtension> { applyProjectStyle() }

  // The Compose compiler Gradle plugin (org.jetbrains.kotlin.plugin.compose)
  // resolves
  // `compose-group-mapping` from this configuration to identify which maven
  // groups are
  // Compose libraries. When its version drifts from the Kotlin version, the
  // plugin can't
  // map the Compose artifacts on the classpath, and the IDE falls back to
  // autodecompiled
  // sources without KDoc for Compose APIs (e.g. Ctrl+click on Box). Force it
  // to match the
  // Kotlin version, exactly like the PicQuery reference project does.
  configurations
    .matching { it.name == "composeMappingProducerClasspath" }
    .configureEach {
      resolutionStrategy.force(
        "org.jetbrains.kotlin:compose-group-mapping:${rootProject.libs.versions.kotlin.get()}"
      )
    }

  afterEvaluate { tasks.named("check") { dependsOn(tasks.getByName("ktfmtCheck")) } }
}

tasks.register<KtfmtFormatTask>("ktfmtPrecommit") {
  source = project.fileTree(rootDir)
  include("**/*.kt")
  include("**/*.kts")
}
