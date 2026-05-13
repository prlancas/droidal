package com.prlancas.droidal.brain

import android.util.Log

/**
 * Pure policy that turns one *user turn* into a single hand-off to
 * the LLM, even when STT splits it across consecutive sessions.
 *
 * Android's one-shot `SpeechRecognizer` will close after a few
 * seconds of silence even with the patient pause extras enabled.
 * Real human speech often pauses for thought, e.g.:
 *
 *   "Tell me about…" (3 s pause) "…the weather"
 *
 * Without aggregation Droidal hands "Tell me about" to the LLM and
 * the trailing fragment gets parsed as a new turn (or lost). With
 * aggregation, the second segment is concatenated onto the first
 * and the LLM sees one coherent sentence.
 *
 * Algorithm:
 *   1. Listen patiently for the first segment.
 *      - If blank/null returned, the turn is empty; bubble up.
 *   2. After a non-blank segment, run a short *tail listen* capped at
 *      [tailMs]. If it returns more text, concatenate (joined by a
 *      single space) and repeat. If it returns blank/null we treat
 *      the user as finished and return the accumulated text.
 *
 * All Android dependencies are dependency-injected so tests can run
 * in plain JVM with scripted listen callbacks.
 */
internal object ListenAggregator {

    private const val TAG = "ListenAggregator"

    /** Default tail-listen window. Tuned to cover a normal mid-sentence
     *  hesitation ("um", "let me think") without making short replies
     *  ("yes", "no") feel sluggish. */
    const val DEFAULT_TAIL_MS: Long = 4_000L

    /**
     * Listen for one logical user turn, transparently concatenating
     * across short pauses.
     *
     * @param tailMs budget passed to [tailSegment] for each
     *   continuation listen. Implementations are free to use it as a
     *   hard cap or as a hint; for the production wiring on Android's
     *   patient SpeechRecognizer it acts as a hint and the recogniser's
     *   own end-of-speech VAD closes the session.
     * @param firstSegment listens patiently for the start of the turn;
     *   returns non-blank text or `null` when the user is genuinely
     *   silent past the listen policy's budget.
     * @param tailSegment listens silently for a continuation, capped at
     *   [tailMs]. Returns non-blank text to concatenate, or `null` /
     *   blank to terminate the turn.
     * @return the concatenated turn (never blank when non-null), or
     *   `null` when the very first segment came back empty.
     */
    suspend fun listenOneTurn(
        tailMs: Long = DEFAULT_TAIL_MS,
        firstSegment: suspend () -> String?,
        tailSegment: suspend (budgetMs: Long) -> String?,
    ): String? {
        val first = firstSegment()
        if (first.isNullOrBlank()) return null
        val acc = StringBuilder(first.trim())
        var segments = 1
        while (true) {
            val tail = tailSegment(tailMs)
            if (tail.isNullOrBlank()) {
                Log.d(TAG, "Turn finalised after $segments segment(s): \"$acc\"")
                return acc.toString()
            }
            acc.append(' ').append(tail.trim())
            segments++
            // Soft warning if a turn keeps extending pathologically;
            // we don't cap it (cutting the user off is worse) but log
            // so it's visible if a recogniser ever gets stuck looping.
            if (segments == 8) {
                Log.w(TAG, "Turn has grown to 8 segments — still listening, but worth a look")
            }
        }
    }
}
