package com.prlancas.droidal.speech

/**
 * Pure routing decision for what to do with a finalised STT
 * transcription.
 *
 * Sits in front of `SpeechToText.deliverResult` so the dispatch
 * — does this text look like a `debug …` command, or is it a real
 * user utterance for the agent? — can be unit-tested without
 * spinning up a real Android `SpeechRecognizer`.
 *
 * The contract that callers rely on: [onComplete] is *always*
 * invoked exactly once, even on the debug-command branch. The
 * historical bug this protects against is that
 * `deliverResult` used to skip [onComplete] for debug commands,
 * leaving `Agent.haveConversation`'s suspended `listenSuspend`
 * waiting on a deferred that would never resolve. That kept
 * `Agent.isChatting()` true forever, which kept the wake-word
 * loop suppressed, which is why "after a debug command Droidal
 * never wakes again" used to be a real complaint.
 *
 * Keeping the dispatch in a tiny pure object also means the
 * `expectedToolNames`-style guard test in
 * [SpeechResultRouterTest] can pin the contract for future
 * refactors.
 */
internal object SpeechResultRouter {

    /** Prefix that marks a transcription as a `debug …` command. */
    const val DEBUG_PREFIX = "debug"

    /**
     * Route a finalised STT [text] either to [onDebug] (when it
     * starts with [DEBUG_PREFIX], case-insensitive) or to the
     * agent via [onComplete]. [onComplete] is invoked exactly
     * once on every path:
     * - real utterance → invoked with the text
     * - debug command  → invoked with `null` after running [onDebug]
     *
     * The `null` invocation on the debug branch is what lets the
     * agent's listen suspend resolve so the conversation can end
     * gracefully and wake-word detection can resume. Without it,
     * Droidal hangs in a half-listening state until the app is
     * restarted.
     */
    fun route(
        text: String,
        onComplete: (String?) -> Unit,
        onDebug: (String) -> Unit,
    ) {
        if (text.startsWith(DEBUG_PREFIX, ignoreCase = true)) {
            onDebug(text)
            onComplete(null)
        } else {
            onComplete(text)
        }
    }
}
