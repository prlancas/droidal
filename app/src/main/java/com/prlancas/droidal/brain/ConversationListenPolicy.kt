package com.prlancas.droidal.brain

import android.util.Log
import kotlinx.coroutines.delay

/**
 * Pure policy: how Droidal recovers between turns when the user is
 * silent or thinking.
 *
 * The listening contract is wall-clock-driven, not retry-count-driven:
 *
 *  - On every blank STT result, restart the listen *silently and
 *    immediately* (the recogniser's own "Pardon?" / "I didn't hear
 *    anything" prompts are suppressed via `silent = true`). Short
 *    thoughtful pauses never produce any verbal nudge from Droidal.
 *  - We give up after [quietBudgetMs] of total silence — but only
 *    *gracefully*. If the user was clearly here shortly before the
 *    budget elapsed (per [voiceHeardWithinMs] OR
 *    [faceVisibleWithinMs]) we play one soft [softPrompt]
 *    (e.g. `Filler.sayStillThere()`) and extend the budget once. If
 *    a second budget elapses, or if neither signal is recent in the
 *    first place, we return `null` so the caller can end the
 *    conversation cleanly.
 *  - If the recogniser keeps returning blank *instantly* (well below
 *    the realistic ~1-3 s of a real STT cycle), we treat that as a
 *    broken-recogniser signal rather than a thoughtful pause: a
 *    [MIN_LISTEN_MS] back-off prevents the listening beep from
 *    machine-gunning, and after [MAX_INSTANT_BLANKS] consecutive
 *    instant blanks we bail out so the user can wake Droidal again
 *    cleanly. This is what stops the "beep every half-second"
 *    feedback loop after a session-leak / mic-busy cascade.
 *
 * Why two presence signals? Voice tells us "the user is mid-sentence,
 * thinking out loud"; face tells us "the user is still in the room
 * looking at me". A thoughtful pause where the user is clearly
 * standing in front of Droidal (face yes, voice quiet) deserves the
 * same patience as a verbal hesitation — abandoning them mid-thought
 * because they stopped making noise for ten seconds is the opposite
 * of human conversation.
 *
 * The function is suspendable so callers can plug in real I/O (Android
 * `SpeechRecognizer`) without blocking; tests can plug in a scripted
 * sequence of replies.
 *
 * All Android dependencies are dependency-injected ([nowMs],
 * [voiceHeardWithinMs], [faceVisibleWithinMs], [softPrompt], [listen],
 * [sleepMs]) so the whole policy runs in plain JVM unit tests.
 */
internal object ConversationListenPolicy {

    private const val TAG = "ConversationListenPolicy"

    /** Default budget for one quiet stretch — chosen to feel patient
     *  but not abandoned. The agent uses this directly. */
    const val DEFAULT_QUIET_BUDGET_MS: Long = 20_000L

    /** Voice spike must have been within this window for "still mid-
     *  thought" to be the more likely explanation than "user has walked
     *  away". Sets the bar for whether [softPrompt] fires at all. */
    const val RECENT_VOICE_WINDOW_MS: Long = 6_000L

    /** Face must have been visible within this window to count as
     *  "still here, just thinking". Slightly longer than the voice
     *  window because a momentary turn of the head shouldn't end the
     *  conversation, but a five-minute disappearance should. */
    const val RECENT_FACE_WINDOW_MS: Long = 10_000L

    /** Minimum wall-clock time a real STT cycle takes. A blank that
     *  came back faster than this is the recogniser bouncing — mic
     *  busy, audio focus lost, or the OEM erroring out before opening
     *  the stream. Back off this long before retrying so the
     *  "listening" notification beep doesn't fire at machine-gun
     *  speed. Tuned for Samsung's Bixby-backed recogniser, which
     *  legitimately takes 1-3 s per cycle when actually listening. */
    const val MIN_LISTEN_MS: Long = 1000L

    /** After this many consecutive instant blanks (under
     *  [MIN_LISTEN_MS] each), assume the recogniser is in an
     *  unrecoverable state and bail out. The agent's outer error
     *  handling will speak a friendly apology and return to wake
     *  word — much better UX than spinning forever. */
    const val MAX_INSTANT_BLANKS: Int = 8

    /**
     * Listen patiently for the user's next turn.
     *
     * @param quietBudgetMs how long to stay silently listening before
     *   considering the user "actually quiet". Passed twice — once
     *   initially, once more after [softPrompt] fires.
     * @param listen invoked with `silent = true` on every attempt (the
     *   recogniser's built-in error speech is always suppressed; the
     *   soft prompt is the only Drodal-driven verbal nudge). Returns
     *   the recognised utterance or `null` / blank for "didn't hear".
     * @param voiceHeardWithinMs returns ms since the last RMS voice
     *   spike, or [Long.MAX_VALUE] when no voice has been heard yet
     *   in this listen.
     * @param faceVisibleWithinMs returns ms since the camera last saw
     *   the user's face, or [Long.MAX_VALUE] when no face is being
     *   tracked. Defaults to `{ Long.MAX_VALUE }` for callers that
     *   don't have a camera signal (or for unit tests). When recent
     *   (≤ [RECENT_FACE_WINDOW_MS]) it's a strong "user is still
     *   here" vote that also triggers the soft-prompt-and-extend path.
     * @param softPrompt invoked at most once across the entire call,
     *   right after the first budget elapses if voice was heard
     *   recently OR a face is currently visible. Production passes
     *   `Filler.sayStillThere`; tests capture it to a list.
     * @param nowMs wall-clock provider; defaults to
     *   [System.currentTimeMillis]. Tests inject a mutable clock.
     * @param sleepMs back-off function called when the recogniser
     *   returns blank instantly (under [MIN_LISTEN_MS]). Defaults to
     *   `kotlinx.coroutines.delay`. Tests use `runTest`'s virtual
     *   scheduler so they don't actually wait.
     * @return the first non-blank utterance heard, or `null` if both
     *   budgets elapsed without one, the instant-blank circuit
     *   breaker tripped, or the first budget elapsed with neither
     *   voice nor face activity.
     */
    @Suppress("LongParameterList")
    suspend fun listenPatiently(
        quietBudgetMs: Long = DEFAULT_QUIET_BUDGET_MS,
        listen: suspend (silent: Boolean) -> String?,
        voiceHeardWithinMs: () -> Long,
        faceVisibleWithinMs: () -> Long = { Long.MAX_VALUE },
        softPrompt: () -> Unit,
        nowMs: () -> Long = System::currentTimeMillis,
        sleepMs: suspend (Long) -> Unit = { delay(it) },
    ): String? {
        require(quietBudgetMs > 0) { "quietBudgetMs must be > 0" }
        val start = nowMs()
        var deadline = start + quietBudgetMs
        var softPromptUsed = false
        var instantBlankStreak = 0
        var result: String? = null
        var done = false
        while (!done) {
            val listenStart = nowMs()
            val heard = listen(true)
            val now = nowMs()
            val elapsed = now - listenStart
            when {
                !heard.isNullOrBlank() -> {
                    result = heard
                    done = true
                }
                now < deadline -> {
                    // Still inside the budget — silently restart STT,
                    // no verbal feedback, the user is probably
                    // thinking. BUT if the listen call itself
                    // returned blank in less than MIN_LISTEN_MS, the
                    // recogniser didn't actually engage; back off
                    // before retrying (otherwise the listening beep
                    // machine-guns) and bail entirely after
                    // MAX_INSTANT_BLANKS in a row.
                    if (elapsed < MIN_LISTEN_MS) {
                        instantBlankStreak++
                        if (instantBlankStreak >= MAX_INSTANT_BLANKS) {
                            Log.w(
                                TAG,
                                "Recogniser returned blank instantly $instantBlankStreak " +
                                    "times in a row — bailing to avoid a beep loop",
                            )
                            done = true
                        } else {
                            val backoff = MIN_LISTEN_MS - elapsed
                            Log.d(
                                TAG,
                                "Instant blank #$instantBlankStreak (${elapsed}ms) — " +
                                    "backing off ${backoff}ms",
                            )
                            sleepMs(backoff)
                        }
                    } else {
                        // Real STT cycle that came back blank — reset the
                        // streak and patient-retry as before.
                        instantBlankStreak = 0
                        Log.d(
                            TAG,
                            "Blank listen at ${now - start}ms (deadline ${deadline - start}ms)",
                        )
                    }
                }
                softPromptUsed -> {
                    // We already nudged once and the user is still
                    // quiet — they've genuinely stopped; let the caller
                    // end the conversation gracefully.
                    Log.i(TAG, "Quiet after soft prompt — ending listen")
                    done = true
                }
                userIsStillHere(voiceHeardWithinMs, faceVisibleWithinMs) -> {
                    // Recent voice spike OR a visible face = "user is
                    // probably mid-thought / waiting for me". Soft-nudge
                    // once and grant another budget. Reset the
                    // instant-blank streak too — a real elapsed budget
                    // is unrelated to the recogniser-bouncing case.
                    val sinceVoice = voiceHeardWithinMs()
                    val sinceFace = faceVisibleWithinMs()
                    Log.i(
                        TAG,
                        "Quiet but presence detected (voice ${sinceVoice}ms ago, " +
                            "face ${sinceFace}ms ago) — soft prompt + extend",
                    )
                    softPrompt()
                    softPromptUsed = true
                    deadline = now + quietBudgetMs
                    instantBlankStreak = 0
                }
                else -> {
                    // No voice and no face for a while — user has
                    // likely walked away.
                    val sinceVoice = voiceHeardWithinMs()
                    val sinceFace = faceVisibleWithinMs()
                    Log.i(
                        TAG,
                        "Quiet with no recent presence (voice ${sinceVoice}ms ago, " +
                            "face ${sinceFace}ms ago) — ending listen",
                    )
                    done = true
                }
            }
        }
        return result
    }

    /**
     * "Should we be patient and extend the budget?" — true when EITHER
     * the user spoke recently (mid-thought) OR their face is currently
     * visible (still here, just quiet). Either signal is enough; we
     * only treat the user as gone when both have been stale for longer
     * than their respective windows.
     */
    private fun userIsStillHere(
        voiceHeardWithinMs: () -> Long,
        faceVisibleWithinMs: () -> Long,
    ): Boolean =
        voiceHeardWithinMs() <= RECENT_VOICE_WINDOW_MS ||
            faceVisibleWithinMs() <= RECENT_FACE_WINDOW_MS
}
