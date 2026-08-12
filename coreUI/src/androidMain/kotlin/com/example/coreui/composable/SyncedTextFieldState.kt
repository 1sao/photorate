package com.example.coreui.composable

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow

/** Provides two-way sync of the [TextFieldState] with the given text state. */
@Composable
fun rememberSyncedTextFieldState(
  text: String,
  onChange: (text: String) -> Unit,
): TextFieldState {
  val textFieldState = rememberTextFieldState(text)

  LaunchedEffect(text) {
    if (textFieldState.text.toString() != text) {
      textFieldState.setTextAndPlaceCursorAtEnd(text)
    }
  }
  LaunchedEffect(textFieldState) {
    snapshotFlow { textFieldState.text.toString() }.collect { text -> onChange(text) }
  }

  return textFieldState
}
