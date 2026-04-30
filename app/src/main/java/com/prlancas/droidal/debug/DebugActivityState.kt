package com.prlancas.droidal.debug

/**
 * High-level "what is Droidal doing right now?" states surfaced via the
 * debug overlay. Producers (`Listen`, `SpeechToText`, `Speak`, `Agent`,
 * `DroidalTools`) push transitions through [DebugBus]; the on-screen
 * overlay subscribes to render the current state.
 *
 * The set is deliberately coarse — anything finer than this gets noisy
 * on the overlay and is better captured in the in-memory
 * [ConversationLog].
 */
enum class DebugActivityState(val label: String) {
    IDLE("Idle"),
    LISTENING_FOR_WAKE_WORD("Listening for wake word"),
    LISTENING_TO_USER("Listening to user"),
    CALLING_LLM("Calling LLM"),
    SPEAKING("Speaking"),
    WEB_SEARCHING("Web searching"),
    TOOL_CALL("Tool call"),
    REFLECTING("Reflecting on memory"),
    NEWS_SCOUTING("News scouting"),
}
