import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    // kmplitert-core-jvm 0.1.4 is compiled for Java 21 (class file 65.0), so
    // the JVM target (smoke tests) needs a 21 toolchain. Android/iOS targets
    // are unaffected.
    jvmToolchain(21)
    // https://kotlinlang.org/docs/multiplatform-expect-actual.html#expected-and-actual-classes
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    android {
        // Unique per-module namespace required by AGP (AndroidManifest merger).
        // Kotlin packages stay `isao.photorate.*`; this only affects the Android
        // R/manifest package of this module.
        namespace = "isao.photorate.photoslitert"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        androidResources.enable = true
    }
    // JVM target: kmplitert-core-jvm bundles the LiteRT darwin/linux dylibs, so
    // the same vision pipeline runs real inference on the host (smoke tests).
    jvm()

    // kmplitert-core publishes no iosX64 klib (arm64 device + simulator only),
    // so the module mirrors that target set. `shared` only consumes
    // photosLiteRT from androidMain, so the missing iosX64 is harmless.
    listOf(
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

        // iOS + JVM share the kmplitert-backed engine (LiteRTCompiler/TFBuffer).
        // Android keeps the custom CompiledModel code, so kmplitert stays off
        // the android classpath entirely.
        val kmpMain by creating { dependsOn(commonMain.get()) }
        // With only two iOS targets the default hierarchy skips the intermediate
        // iosMain set (it is auto-created when iosX64 is present too), so declare
        // it explicitly to host the shared iOS adapter code in src/iosMain.
        val iosMain by creating { dependsOn(kmpMain) }
        getByName("jvmMain").dependsOn(kmpMain)
        getByName("iosArm64Main").dependsOn(iosMain)
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)

        commonMain.dependencies {
            // The LiteRT provider implements the photosInference contracts
            // (HandLandmarkerFactory + AppClipSearchFactory) so the app can run
            // hand-landmark and MobileCLIP search inference on LiteRT.
            implementation(projects.photosInference)
        }
        kmpMain.dependencies {
            // Cross-platform LiteRT engine (JVM: JNA + bundled dylib; iOS:
            // native C-API). The platform seam below creates LiteRTCompiler
            // instances per target.
            implementation("io.github.leitingzi:kmplitert-core:0.1.4")
            // runBlocking bridges the suspend kmplitert API to the sync pipeline.
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        getByName("jvmTest").dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            // LiteRT runtime + CompiledModel API (com.google.ai.edge.litert.*)
            // with the OpenCL/GL GPU accelerator. 2.1.6 is the version the
            // google-ai-edge/litert-samples image_segmentation sample pins.
            implementation("com.google.ai.edge.litert:litert:2.1.6")
            // `javax.inject.Inject` on the factories mirrors photosOnnx/
            // photosMediaPipe (used by the app's androidTest dataset tests).
            implementation("javax.inject:javax.inject:1")
        }
    }
}

// The smoke test loads the 55 MB RTMPose model and large pixel buffers;
// the default Gradle test heap is far too small.
tasks.withType<Test>().configureEach {
    maxHeapSize = "4g"
}
