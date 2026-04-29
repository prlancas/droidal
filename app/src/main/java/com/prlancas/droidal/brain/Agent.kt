package com.prlancas.droidal.brain

import android.util.Log
import com.prlancas.droidal.brain.llm.LlmProviderFactory
import com.prlancas.droidal.brain.tools.DroidalTools
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.SuspendLatch
import com.prlancas.droidal.event.events.Say
import com.prlancas.droidal.event.events.StartConversation
import com.prlancas.droidal.listen.Listen
import com.prlancas.droidal.memory.Memory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates a back-and-forth conversation with the user.
 *
 * Provider-agnostic: [LlmProviderFactory.current] returns either
 * [com.prlancas.droidal.brain.llm.GeminiRestProvider] (cloud) or
 * [com.prlancas.droidal.brain.llm.LiteRtLmProvider] (on-device). Both expose
 * the same session-based API, and both wire the same [DroidalTools] instance
 * so the model can drive the robot (`move`) or update memory
 * (`setName`/`addInterest`) regardless of where inference runs.
 *
 * No Koog dependency — tool-calling is handled either by LiteRT-LM's native
 * `ToolProvider` (local) or by a small function-call loop in
 * `GeminiRestProvider` (cloud).
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
object Agent {

    private const val TAG = "AGENT"

    private val scope = MainScope()
    private val chatting = AtomicBoolean(false)

    init {
        scope.launch(newFixedThreadPoolContext(10, "LLMThreads")) {
            EventBus.subscribe<StartConversation> { event ->
                if (chatting.compareAndSet(false, true)) {
                    CoroutineScope(Dispatchers.Default).launch {
                        haveConversation(event)
                    }
                }
            }
        }
    }

    suspend fun haveConversation(startConversation: StartConversation) {
        try {
            val context = Config.getContext()
            val provider = LlmProviderFactory.current(context)
            val systemPrompt = Memory.generateSystemPrompt(startConversation)
            val initialMessage = if (startConversation.startedByUser) {
                startConversation.message
            } else {
                "Start a conversation with me!"
            }
            Log.i(TAG, "Conversation starting on ${provider.displayName}")

            val session = provider.newSession(systemPrompt, DroidalTools())
            // If the local engine had to fall back (e.g. GPU → CPU), warn
            // the user once so long responses don't look like a hang.
            com.prlancas.droidal.brain.llm.LiteRtLmEngineCache.takeWarning()?.let {
                EventBus.publishAsync(Say(it))
            }
            try {
                var reply = session.send(initialMessage)
                Log.i(TAG, "Reply: $reply")
                while (!reply.contains("[END_CONVERSATION]")) {
                    val followUp = speakSuspend(reply)
                    if (followUp.isBlank()) break
                    reply = session.send(followUp)
                    Log.i(TAG, "Reply: $reply")
                }
                val tail = reply.replace("[END_CONVERSATION]", "").trim()
                if (tail.isNotBlank()) EventBus.publishAsync(Say(tail))
            } finally {
                session.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in conversation: ${e.message}", e)
            EventBus.publishAsync(Say("LLM error: ${e.message}"))
        } finally {
            chatting.set(false)
        }
    }
}

private suspend fun speakSuspend(message: String): String {
    val suspendLatch = SuspendLatch(1)
    var reply: String? = null
    Log.i("LISTEN", "Speaking and waiting for reply: $message")
    Listen.listenAndReplySuspend(message) { replyMsg ->
        Log.i("LISTEN", "Heard: $replyMsg")
        reply = replyMsg
        suspendLatch.countDown()
    }
    suspendLatch.await()
    return reply ?: ""
}
