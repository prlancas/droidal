package com.prlancas.droidal.debug

import com.prlancas.droidal.config.Config
import com.prlancas.droidal.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide, in-memory ring buffer of conversation events.
 *
 * Populated by `Agent`, `Speak`, `DroidalTools`, `SpeechToText` and
 * [DebugBus] when the user has enabled the debug "conversation log"
 * toggle. Capped at [CAPACITY] entries so a long-running session can't
 * leak memory.
 *
 * The viewer in the debug settings page collects [entries] as a
 * [StateFlow] for live updates.
 */
object ConversationLog {

    private const val CAPACITY = 500

    enum class Kind(val display: String) {
        USER_SAID("User"),
        LLM_REQUEST("LLM →"),
        LLM_RESPONSE("LLM ←"),
        TOOL_CALL("Tool"),
        TOOL_RESULT("Tool ←"),
        ACTIVITY("Activity"),
        SPOKE("Spoke"),
        INFO("Info"),
    }

    data class Entry(
        val timestampMs: Long,
        val kind: Kind,
        val text: String,
    ) {
        fun render(timeFormat: SimpleDateFormat): String =
            "${timeFormat.format(Date(timestampMs))}  ${kind.display}: $text"
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun append(kind: Kind, text: String) {
        if (text.isBlank()) return
        if (!isEnabled()) return
        val entry = Entry(System.currentTimeMillis(), kind, text.trim())
        synchronized(this) {
            val current = _entries.value
            val next = if (current.size >= CAPACITY) {
                current.drop(current.size - CAPACITY + 1) + entry
            } else {
                current + entry
            }
            _entries.value = next
        }
    }

    fun clear() {
        synchronized(this) { _entries.value = emptyList() }
    }

    /**
     * Format the buffer as plain text with one entry per line. Used by
     * the "Copy" / "Share" actions in the viewer.
     */
    fun render(): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.UK)
        return _entries.value.joinToString(separator = "\n") { it.render(fmt) }
    }

    private fun isEnabled(): Boolean = runCatching {
        SettingsRepository.get(Config.getContext()).debugConversationLogEnabled()
    }.getOrDefault(false)
}
