package isao.photorate.coreUi.navigation

/**
 * Handles navigation intents emitted by a module's screens. Implementations live at the app
 * composition root, which decides what each intent means (which route to open, whether to pop).
 */
fun interface IntentHandler<Intent> {
  fun handle(intent: Intent)
}
