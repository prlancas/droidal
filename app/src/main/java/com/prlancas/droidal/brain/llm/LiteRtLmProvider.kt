@file:OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)

package com.prlancas.droidal.brain.llm

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.prlancas.droidal.brain.tools.DroidalTools
import com.prlancas.droidal.settings.data.Model
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * On-device chat provider backed by LiteRT-LM, modelled on
 * [gallery's LlmChatModelHelper](https://github.com/google-ai-edge/gallery).
 *
 * Tool calls use LiteRT-LM's native `ToolProvider` support — the
 * [DroidalTools] instance passed to [newSession] is wrapped with the
 * `tool(...)` factory and handed to `ConversationConfig`, after which
 * LiteRT-LM invokes the annotated methods for us and feeds the results back
 * to the model automatically. From `send`'s point of view, tool calls are
 * invisible.
 *
 * The heavyweight [Engine] is shared via [LiteRtLmEngineCache]; every new
 * session creates a fresh [Conversation] on top.
 */
class LiteRtLmProvider(
    private val appContext: Context,
    private val model: Model,
    private val useLocalForVision: Boolean,
) : LlmProvider {

    override val displayName: String = "Local: ${model.name}"
    override val supportsImages: Boolean = useLocalForVision && model.llmSupportImage

    override fun requiresWarmup(): Boolean = !LiteRtLmEngineCache.isLoaded(model)

    override fun newSession(systemPrompt: String, tools: DroidalTools): ChatSession {
        val engine = LiteRtLmEngineCache.getEngine(appContext, model)
        val conversation = engine.createConversation(
            ConversationConfig(
                samplerConfig = samplerFor(model),
                systemInstruction = Contents.of(listOf(Content.Text(systemPrompt))),
                tools = listOf(tool(tools)),
            ),
        )
        return LiteRtLmSession(conversation)
    }

    override suspend fun describeImage(bitmap: Bitmap, prompt: String): String? {
        if (!supportsImages) return null
        return withContext(Dispatchers.IO) {
            val engine = LiteRtLmEngineCache.getEngine(appContext, model)
            val conv = engine.createConversation(
                ConversationConfig(samplerConfig = samplerFor(model)),
            )
            try {
                sendAsync(
                    conversation = conv,
                    contents = listOf(Content.ImageBytes(bitmap.toPngByteArray()), Content.Text(prompt)),
                )
            } finally {
                runCatching { conv.close() }
            }
        }
    }

    override fun close() {
        // Engine lifetime is owned by LiteRtLmEngineCache.
    }

    companion object {
        private const val TAG = "LiteRtLmProvider"

        internal fun samplerFor(model: Model): SamplerConfig {
            val cfg = model.defaultConfig
            return SamplerConfig(
                topK = cfg.topK ?: 64,
                topP = (cfg.topP ?: 0.95f).toDouble(),
                temperature = (cfg.temperature ?: 1.0f).toDouble(),
            )
        }

        internal fun backendFor(accelerators: String?): Backend {
            val first = accelerators.orEmpty().split(",").map { it.trim().lowercase() }.firstOrNull()
            return when (first) {
                "gpu" -> Backend.GPU()
                "cpu" -> Backend.CPU()
                else -> Backend.CPU()
            }
        }

        internal fun visionBackendFor(accelerator: String?): Backend =
            if (accelerator == "cpu") Backend.CPU() else Backend.GPU()

        /**
         * Send [contents] on [conversation] and await the assistant
         * reply. When [onPartial] is non-null it is invoked with each
         * delta as the model streams it (matching gallery's
         * `MessageCallback.onMessage` contract — every chunk is appended
         * to whatever's been seen so far).
         *
         * `<ctrl…>` control tokens emitted by LiteRT-LM are dropped (they
         * aren't part of the assistant's spoken reply); gallery does the
         * same in `LlmChatViewModel`.
         */
        internal suspend fun sendAsync(
            conversation: Conversation,
            contents: List<Content>,
            onPartial: ((String) -> Unit)? = null,
        ): String = withContext(Dispatchers.IO) {
            val deferred = CompletableDeferred<String>()
            val buffer = StringBuilder()
            conversation.sendMessageAsync(
                Contents.of(contents),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val delta = message.toString()
                        if (delta.isEmpty() || delta.startsWith("<ctrl")) return
                        buffer.append(delta)
                        onPartial?.invoke(delta)
                    }

                    override fun onDone() {
                        if (!deferred.isCompleted) deferred.complete(buffer.toString().trim())
                    }

                    override fun onError(throwable: Throwable) {
                        if (!deferred.isCompleted) deferred.completeExceptionally(throwable)
                    }
                },
                emptyMap(),
            )
            deferred.await()
        }

        private fun Bitmap.toPngByteArray(): ByteArray {
            val stream = ByteArrayOutputStream()
            compress(Bitmap.CompressFormat.PNG, 100, stream)
            return stream.toByteArray()
        }
    }

    private class LiteRtLmSession(
        private val conversation: Conversation,
    ) : ChatSession {
        override suspend fun send(userMessage: String): String =
            sendAsync(conversation, listOf(Content.Text(userMessage)))

        override suspend fun send(userMessage: String, onPartial: (String) -> Unit): String =
            sendAsync(conversation, listOf(Content.Text(userMessage)), onPartial)

        override fun close() {
            runCatching { conversation.close() }.onFailure {
                Log.w(TAG, "Failed to close conversation: ${it.message}")
            }
        }
    }
}
