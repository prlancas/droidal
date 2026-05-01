package com.prlancas.droidal.brain

import android.util.Log

/**
 * Pure policy: how Droidal recovers when STT comes back blank between
 * turns of an in-flight conversation.
 *
 * Behaviour mirrors the original [Agent.listenWithRetry]:
 *
 *  - Attempt the listen; if it returns a non-blank string we're done.
 *  - Each subsequent attempt is **silent** (the underlying STT skips its
 *    built-in `Pardon` / `I didn't hear anything` announcements) so a
 *    long retry streak doesn't fill the room with apologies.
 *  - Exactly once per streak — right after the first blank — Droidal
 *    speaks a single short `"Pardon?"` so the user knows it's still
 *    listening. After that, it's silent for the rest of the streak.
 *  - If [maxBlankRetries] consecutive blanks accumulate we give up and
 *    return `null` so the caller can end the conversation cleanly.
 *
 * The function is suspendable so callers can plug in real I/O (Android
 * `SpeechRecognizer`) without blocking; tests can plug in a scripted
 * sequence of replies.
 *
 * [say] is a side-effecting callback (publish a `Say` event in
 * production, append to a list in tests) so the policy stays free of
 * Android dependencies and easy to unit-test.
 */
internal object ConversationListenPolicy {

    private const val TAG = "ConversationListenPolicy"

    /**
     * @param maxBlankRetries how many consecutive blank STT replies we
     *   tolerate. Total attempts is `maxBlankRetries + 1` (the initial
     *   try + that many retries).
     * @param listen invoked with `silent=false` on the first attempt and
     *   `silent=true` on every retry. Should return the recognised
     *   utterance, or `null` / blank for "didn't hear anything".
     * @param say invoked with `"Pardon?"` exactly once per streak — right
     *   after the first blank. Tests can capture it to verify Droidal
     *   doesn't pile up "Pardon Pardon Pardon".
     * @return the first non-blank utterance heard, or `null` if every
     *   attempt came back blank.
     */
    suspend fun listenWithRetry(
        maxBlankRetries: Int,
        listen: suspend (silent: Boolean) -> String?,
        say: (String) -> Unit,
    ): String? {
        require(maxBlankRetries >= 0) { "maxBlankRetries must be >= 0" }
        repeat(maxBlankRetries + 1) { attempt ->
            val silent = attempt > 0
            val heard = listen(silent)
            if (!heard.isNullOrBlank()) return heard
            Log.i(TAG, "STT returned blank on attempt ${attempt + 1}/${maxBlankRetries + 1}")
            if (attempt == 0) {
                // Single polite nudge before going silent for the rest
                // of the streak.
                say("Pardon?")
            }
        }
        return null
    }
}
