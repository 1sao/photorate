plugins { id("photorate.component") }

kotlin {
  android { namespace = "isao.photorate.trackingcomponentfirebase" }

  sourceSets {
    commonMain.dependencies { implementation(projects.feature.tracking.trackingComponentApi) }
    commonTest.dependencies { implementation(libs.kotlin.test) }
    androidMain.dependencies { implementation(libs.firebase.crashlytics) }
  }
}

dependencies { "androidMainImplementation"(platform(libs.firebase.bom)) }
