package com.prlancas.droidal.brain

import android.util.Log
import com.prlancas.droidal.brain.llm.LlmProviderFactory
import com.prlancas.droidal.brain.tools.DroidalTools
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import com.prlancas.droidal.event.events.StartConversation
import com.prlancas.droidal.listen.Listen
import com.prlancas.droidal.memory.Memory
import com.prlancas.droidal.settings.SettingsRepository
import com.prlancas.droidal.speech.TtsStreamer
import kotlinx.coroutines.CompletableDeferred
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
 * The conversation loop streams the assistant reply through
 * [TtsStreamer] as the model generates tokens, so the user starts
 * hearing the reply within ~1 sentence of generation rather than after
 * the whole response is buffered. After the streamer has drained TTS we
 * call [Listen.listenOnly] for the next user turn — if STT comes back
 * blank (e.g. ERROR_NO_MATCH spoke "Pardon" out loud) we retry once
 * before ending the conversation, so "Pardon" is actually an invitation
 * to re-speak rather than a hidden goodbye.
 *
 * No Koog dependency — tool-calling is handled either by LiteRT-LM's native
 * `ToolProvider` (local) or by a small function-call loop in
 * `GeminiRestProvider` (cloud).
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
object Agent {

    private const val TAG = "AGENT"

    /** Number of times to retry STT after a blank response before bailing. */
    private const val MAX_BLANK_RETRIES = 1

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
            var nextInput = if (startConversation.startedByUser) {
                startConversation.message
            } else {
                "Start a conversation with me!"
            }
            val streamingMode = SettingsRepository.get(context).streamingMode()
            Log.i(TAG, "Conversation starting on ${provider.displayName} (streaming=$streamingMode)")

            val session = provider.newSession(systemPrompt, DroidalTools())
            // If the local engine had to fall back (e.g. GPU → CPU), warn
            // the user once so long responses don't look like a hang.
            com.prlancas.droidal.brain.llm.LiteRtLmEngineCache.takeWarning()?.let {
                EventBus.publishAsync(Say(it))
            }
            try {
                while (true) {
                    val streamer = TtsStreamer(streamingMode)
                    val reply = session.send(nextInput, streamer::feed)
                    streamer.finishAndAwait()
                    Log.i(TAG, "Reply: $reply")

                    // The streamer already truncated at [END_CONVERSATION]
                    // so we never speak it; bail once the model signalled
                    // end-of-conversation.
                    if (reply.contains("[END_CONVERSATION]")) return

                    val followUp = listenWithRetry()
                    if (followUp.isNullOrBlank()) {
                        // STT failed / user didn't speak. The recogniser
                        // already said "Pardon" / similar in
                        // SpeechToText.onError, and we already streamed
                        // the model's reply — re-speaking it would be
                        // confusing.
                        Log.i(TAG, "No follow-up after retry — ending conversation")
                        return
                    }
                    nextInput = followUp
                }
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

    /**
     * Listen for the user's next turn, retrying up to [MAX_BLANK_RETRIES]
     * times if STT comes back null/blank. The recogniser speaks
     * "Pardon" itself on `ERROR_NO_MATCH`, so a retry here means the
     * user gets a second chance to be heard.
     */
    private suspend fun listenWithRetry(): String? {
        repeat(MAX_BLANK_RETRIES + 1) { attempt ->
            val heard = listenSuspend()
            if (!heard.isNullOrBlank()) return heard
            Log.i(TAG, "STT returned blank on attempt ${attempt + 1}; will retry: ${attempt < MAX_BLANK_RETRIES}")
        }
        return null
    }

    /** Wraps [Listen.listenOnly] in a suspending call. */
    private suspend fun listenSuspend(): String? {
        val deferred = CompletableDeferred<String?>()
        Listen.listenOnly { reply -> deferred.complete(reply) }
        return deferred.await()
    }
}
