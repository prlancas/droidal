package com.prlancas.droidal.memory.learning

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-conversation handles set by
 * [com.prlancas.droidal.brain.Agent.haveConversation] for the lifetime of
 * one conversation.
 *
 * The LiteRT-LM tool dispatch and the cloud Gemini / OpenRouter loops all
 * call [com.prlancas.droidal.brain.tools.DroidalTools] without an
 * explicit conversation argument, so the tool methods read the active
 * user / session — and signal "end the conversation" — through this
 * holder.
 *
 * Implementation:
 * - [tlsUserId] / [tlsSessionId] are [ThreadLocal]s because the agent
 *   coroutine bound the conversation to one dispatcher worker. Tool
 *   callbacks always run on that same worker.
 * - [endRequested] is a process-wide [AtomicBoolean] because the LiteRT-LM
 *   native callback that fires `endConversation` may invoke tools on a
 *   different thread. It's cheap (one bit) and we only check it from the
 *   single agent loop.
 */
object LearningContext {

    private val tlsUserId = ThreadLocal<String?>()
    private val tlsSessionId = ThreadLocal<String?>()
    private val endRequested = AtomicBoolean(false)

    val currentUserId: String
        get() = tlsUserId.get() ?: LearningPaths.UNKNOWN_USER

    val currentSessionId: String
        get() = tlsSessionId.get() ?: ""

    fun set(userId: String, sessionId: String) {
        tlsUserId.set(userId)
        tlsSessionId.set(sessionId)
        endRequested.set(false)
    }

    fun clear() {
        tlsUserId.remove()
        tlsSessionId.remove()
        endRequested.set(false)
    }

    /**
     * Signal that the LLM wants to end the current conversation cleanly.
     * Called from the `endConversation` tool — the agent loop checks
     * [wasEndRequested] after each turn drains and bails out.
     */
    fun requestEnd() {
        endRequested.set(true)
    }

    fun wasEndRequested(): Boolean = endRequested.get()
}
