plugins { id("photorate.component") }

kotlin {
  android { namespace = "isao.photorate.trackingcomponentapi" }

  sourceSets { commonTest.dependencies { implementation(libs.kotlin.test) } }
}
