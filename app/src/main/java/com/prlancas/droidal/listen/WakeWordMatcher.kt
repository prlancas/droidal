package com.prlancas.droidal.listen

import android.util.Log
import com.prlancas.droidal.settings.SettingsRepository

/**
 * Decides whether a recognised utterance should wake Droidal and start a
 * conversation, and what text to hand to the LLM.
 *
 * Replaces the old Picovoice Porcupine keyword engine (Picovoice removed
 * their free tier). Instead of a dedicated always-on wake-word detector,
 * [Listen] now continuously transcribes speech with the same Google
 * [android.speech.SpeechRecognizer] used for the conversation, and this
 * matcher gates whether a given transcript should trigger the agent.
 *
 * A transcript wakes Droidal when ANY of these hold (checked in order):
 *  1. [alwaysTrigger] is on — any non-blank speech wakes Droidal.
 *  2. A face is currently visible ([faceVisible]) — someone is looking at
 *     Droidal and talking, so we respond even without the wake word.
 *  3. The transcript matches [regex] — the classic "hey Droidal…" phrase.
 *     Case-insensitivity is expressed in the pattern itself (the default
 *     uses an inline `(?i)` flag).
 *
 * Pure / Android-free (bar [Log], which unit tests stub to a no-op) so the
 * whole decision can be exercised on the host JVM — see
 * `WakeWordMatcherTest`.
 */
class WakeWordMatcher private constructor(
    private val regex: Regex?,
    private val alwaysTrigger: Boolean,
) {

    /**
     * @param wake whether this transcript should start a conversation.
     * @param message the text to hand to the LLM — the full recognised
     *   utterance when [wake] is true (the wake word is left in; the LLM
     *   handles being addressed by name), otherwise blank.
     */
    data class Decision(val wake: Boolean, val message: String)

    fun evaluate(transcript: String, faceVisible: Boolean): Decision {
        val text = transcript.trim()
        if (text.isEmpty()) return NO_WAKE
        val wake = alwaysTrigger || faceVisible || (regex?.containsMatchIn(text) == true)
        return if (wake) Decision(true, text) else NO_WAKE
    }

    companion object {
        private const val TAG = "WakeWordMatcher"

        private val NO_WAKE = Decision(false, "")

        fun fromSettings(settings: SettingsRepository): WakeWordMatcher =
            build(settings.wakeRegex(), settings.wakeAlwaysTrigger())

        fun build(pattern: String, alwaysTrigger: Boolean): WakeWordMatcher {
            val regex = compile(pattern)
                ?: compile(SettingsRepository.DEFAULT_WAKE_REGEX)
            return WakeWordMatcher(regex, alwaysTrigger)
        }

        private fun compile(pattern: String): Regex? =
            runCatching { Regex(pattern) }
                .onFailure { Log.w(TAG, "Invalid wake regex \"$pattern\": ${it.message}") }
                .getOrNull()
    }
}
