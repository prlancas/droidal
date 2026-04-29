package com.prlancas.droidal.brain.llm

import android.graphics.Bitmap
import com.prlancas.droidal.brain.tools.DroidalTools

/**
 * A single back-and-forth conversation with an LLM. Maintains its own
 * history internally — the caller just `.send(...)`s the next user turn
 * and gets the assistant reply back. Any tool calls the model makes are
 * handled transparently within `send`.
 */
interface ChatSession {
    suspend fun send(userMessage: String): String

    /**
     * Streaming variant of [send]. [onPartial] is invoked, possibly from
     * a callback / IO thread, with each new chunk of text produced by
     * the model. The full assistant reply is also returned (same as
     * [send]) so callers can inspect it (e.g. for `[END_CONVERSATION]`).
     *
     * The default implementation falls back to non-streaming [send] and
     * emits the entire reply as a single delta — useful for cloud REST
     * providers that don't naturally stream. Implementations that *do*
     * stream (e.g. LiteRT-LM) should override this and forward each
     * `MessageCallback.onMessage` chunk to [onPartial].
     */
    suspend fun send(userMessage: String, onPartial: (String) -> Unit): String {
        val full = send(userMessage)
        if (full.isNotEmpty()) onPartial(full)
        return full
    }

    fun close()
}

/**
 * Provider-agnostic façade over cloud Gemini and on-device LiteRT-LM.
 *
 * Created via [LlmProviderFactory.current]. Use [newSession] to start a
 * stateful conversation; use [describeImage] for one-shot vision when the
 * provider supports it.
 */
interface LlmProvider {
    val displayName: String

    /** True when this provider can return a text description for [describeImage]. */
    val supportsImages: Boolean

    fun newSession(systemPrompt: String, tools: DroidalTools): ChatSession

    /** One-shot vision call. Returns null when this provider cannot do vision. */
    suspend fun describeImage(bitmap: Bitmap, prompt: String): String?

    /** Release any persistent engine / HTTP clients. */
    fun close() {}
}
