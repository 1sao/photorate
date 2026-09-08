package isao.photorate.imageRecognition

import isao.photorate.core.Failure

sealed interface ResourceFailure : Failure {
  data class PermissionDenied(val uri: String) : ResourceFailure

  data class NotFound(val uri: String) : ResourceFailure

  data class DecodeFailed(val uri: String) : ResourceFailure
}
