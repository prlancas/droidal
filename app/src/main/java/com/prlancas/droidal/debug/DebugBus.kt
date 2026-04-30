package com.prlancas.droidal.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide hub for the debug overlay rendered on [com.prlancas.droidal.ui.FaceCanvas].
 *
 * Two reactive surfaces:
 * - [activity]: the current [DebugActivityState] (e.g. CALLING_LLM, SPEAKING).
 * - [partialSpeech]: the most recent partial transcription from the
 *   speech recogniser, displayed live so users can see what Droidal is
 *   hearing while they talk.
 *
 * Both are unconditionally updated by their producers — the on-screen
 * overlay chooses whether to render based on the user's debug settings
 * (see [com.prlancas.droidal.settings.SettingsRepository]). This keeps
 * producers cheap (no per-event preference reads) and lets the
 * conversation log keep recording transitions even when nothing is
 * visible on screen.
 *
 * Activity changes also funnel into [ConversationLog] so toggling
 * "conversation log" on captures the full state machine, not just the
 * spoken text.
 */
object DebugBus {

    private val _activity = MutableStateFlow(DebugActivityState.IDLE)
    val activity: StateFlow<DebugActivityState> = _activity.asStateFlow()

    private val _partialSpeech = MutableStateFlow("")
    val partialSpeech: StateFlow<String> = _partialSpeech.asStateFlow()

    fun setActivity(state: DebugActivityState, detail: String? = null) {
        if (_activity.value == state && detail == null) return
        _activity.value = state
        ConversationLog.append(
            ConversationLog.Kind.ACTIVITY,
            if (detail.isNullOrBlank()) state.label else "${state.label} — $detail",
        )
    }

    fun clearActivity() = setActivity(DebugActivityState.IDLE)

    fun setPartialSpeech(text: String) {
        _partialSpeech.value = text
    }

    fun clearPartialSpeech() {
        _partialSpeech.value = ""
    }

    /**
     * Run [block] with the activity pinned to [state], restoring the
     * previous activity afterwards. Useful for short-lived operations
     * (web search, tool call) so we don't have to remember to clear.
     */
    inline fun <T> withActivity(state: DebugActivityState, detail: String? = null, block: () -> T): T {
        val previous = activity.value
        setActivity(state, detail)
        return try {
            block()
        } finally {
            setActivity(previous)
        }
    }
}
