plugins {
    alias(libs.plugins.ktlint) apply false
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

subprojects {
    apply(plugin = rootProject.libs.plugins.ktlint.get().pluginId)

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.4.0")
        enableExperimentalRules.set(true)
        verbose.set(true)
        filter {
            exclude { it.file.path.contains("build/") }
        }
    }

    // The Compose compiler Gradle plugin (org.jetbrains.kotlin.plugin.compose) resolves
    // `compose-group-mapping` from this configuration to identify which maven groups are
    // Compose libraries. When its version drifts from the Kotlin version, the plugin can't
    // map the Compose artifacts on the classpath, and the IDE falls back to autodecompiled
    // sources without KDoc for Compose APIs (e.g. Ctrl+click on Box). Force it to match the
    // Kotlin version, exactly like the PicQuery reference project does.
    configurations.matching { it.name == "composeMappingProducerClasspath" }.configureEach {
        resolutionStrategy.force("org.jetbrains.kotlin:compose-group-mapping:${rootProject.libs.versions.kotlin.get()}")
    }

    afterEvaluate {
        tasks.named("check") {
            dependsOn(tasks.getByName("ktlintCheck"))
        }
    }
}
