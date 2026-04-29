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
