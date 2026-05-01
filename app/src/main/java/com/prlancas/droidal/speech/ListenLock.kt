package com.prlancas.droidal.speech

/**
 * Tiny thread-safe lock around an STT recognition session.
 *
 * Two callers can race into [SpeechToText.startListening] before the
 * first one's `onReadyForSpeech` callback has marked the session
 * "in flight" — without a lock, that produces two SpeechRecognizer
 * instances fighting over the microphone (one of them eventually fires
 * `ERROR_RECOGNIZER_BUSY` and the conversation drops a turn).
 *
 * [tryStart] is an atomic test-and-set. The owning caller is responsible
 * for eventually invoking [release] from every code path that ends the
 * recognition session (results, error, timeout, explicit `stopListening`,
 * `destroy`).
 *
 * Extracted from [SpeechToText] so the contract can be exercised in plain
 * JVM unit tests (no Android dependencies).
 */
internal class ListenLock {

    @Volatile private var listening = false
    private val lock = Any()

    /**
     * Claim the recognition slot. Returns `true` exactly once until
     * [release] is called; subsequent callers get `false` so they can
     * politely bail out (call their `onComplete` callback with `null`
     * and return) instead of starting a competing session.
     */
    fun tryStart(): Boolean = synchronized(lock) {
        if (listening) {
            false
        } else {
            listening = true
            true
        }
    }

    /** Release the recognition slot. Idempotent. */
    fun release() {
        synchronized(lock) { listening = false }
    }

    /** True while a recognition session is in flight. */
    fun isListening(): Boolean = synchronized(lock) { listening }
}
