package isao.photorate.core

interface AppInfo {
  val appId: String
  val versionName: String
  val versionCode: Int

  companion object {
    val Empty: AppInfo =
      object : AppInfo {
        override val appId = ""
        override val versionName = ""
        override val versionCode = 0
      }
  }
}
