import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqlDelight)
    alias(libs.plugins.koin.compiler)
}

koinCompiler {
    compileSafety = true
}

kotlin {
    jvmToolchain(11)
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    android {
        namespace = "isao.photorate.config"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        androidResources.enable = true
    }
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach {
        it.binaries.framework {
            isStatic = true
        }
    }

    sourceSets {
        all {
            languageSettings.apply {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            }
        }

        commonMain.dependencies {
            implementation(projects.core)
            implementation(libs.koin.core)
            implementation(libs.koin.annotations)
            implementation(libs.coroutines.core)
        }
    }
}

sqldelight {
    databases.create("PhotoRateDb") {
        packageName.set("isao.photorate.config.db")
        dialect("app.cash.sqldelight:sqlite-3-30-dialect:${libs.versions.sqlDelight.get()}")
    }
}
