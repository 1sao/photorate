plugins { id("photorate.component") }

kotlin {
  // kmplitert-core-jvm 0.1.4 is compiled for Java 21 (class file 65.0), so
  // the JVM target (smoke tests) needs a 21 toolchain. Android/iOS targets
  // are unaffected.
  jvmToolchain(21)
  android { namespace = "isao.photorate.photoslitert" }

  sourceSets {
    val kmpMain by creating { dependsOn(commonMain.get()) }
    val iosMain by creating { dependsOn(kmpMain) }
    getByName("jvmMain").dependsOn(kmpMain)
    getByName("iosArm64Main").dependsOn(iosMain)
    getByName("iosSimulatorArm64Main").dependsOn(iosMain)

    commonMain.dependencies {
      implementation(projects.feature.imageRecognition.imageRecognitionComponentApi)
    }
    kmpMain.dependencies {
      implementation(libs.kmplitert.core)
      implementation(libs.coroutines.core)
    }
    commonTest.dependencies { implementation(libs.kotlin.test) }
    getByName("jvmTest").dependencies { implementation(libs.kotlin.test) }
    androidMain.dependencies {
      implementation(libs.litert)
      implementation(libs.javax.inject)
    }
  }
}

// The smoke test loads the 55 MB RTMPose model and large pixel buffers;
// the default Gradle test heap is far too small.
tasks.withType<Test>().configureEach { maxHeapSize = "4g" }
