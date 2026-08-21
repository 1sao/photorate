package isao.photorate.tracking

interface CrashReporter {
  fun logNonFatal(throwable: Throwable, key: String, extras: Map<String, String> = emptyMap())
}
