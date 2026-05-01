package com.prlancas.droidal.brain

import android.util.Log
import com.prlancas.droidal.brain.llm.LlmProviderFactory
import com.prlancas.droidal.brain.tools.DroidalTools
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.debug.ConversationLog
import com.prlancas.droidal.debug.DebugActivityState
import com.prlancas.droidal.debug.DebugBus
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import com.prlancas.droidal.event.events.StartConversation
import com.prlancas.droidal.listen.Listen
import com.prlancas.droidal.memory.learning.LearningContext
import com.prlancas.droidal.memory.learning.LearningDatabase
import com.prlancas.droidal.memory.learning.LearningPaths
import com.prlancas.droidal.memory.learning.LearningStore
import com.prlancas.droidal.memory.learning.workers.ReflectorWorker
import com.prlancas.droidal.settings.SettingsRepository
import com.prlancas.droidal.speech.Filler
import com.prlancas.droidal.speech.TtsStreamer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates a back-and-forth conversation with the user.
 *
 * Provider-agnostic: [LlmProviderFactory.current] returns either
 * [com.prlancas.droidal.brain.llm.GeminiRestProvider] (cloud) or
 * [com.prlancas.droidal.brain.llm.LiteRtLmProvider] (on-device). Both expose
 * the same session-based API, and both wire the same [DroidalTools] instance
 * so the model can drive the robot, update memory, and manage skills
 * regardless of where inference runs.
 *
 * Per-user learning hooks (mirrors hermes-agent's `MemoryManager`):
 * - [LearningContext] is pushed for the duration of the conversation so
 *   tool calls can resolve the active user/session.
 * - The full system prompt is assembled by
 *   [LearningStore.systemPromptBlock] (frozen MEMORY + USER markdown +
 *   skills index + recent-session digest + pending news primers).
 * - Every completed user/assistant turn is recorded in the conversation log
 *   so the FTS-backed `searchMemory` tool and the background reflector can
 *   see it.
 * - On natural session end we enqueue a one-shot [ReflectorWorker] for that
 *   userId so the auxiliary LLM can promote facts into MEMORY.md / USER.md
 *   while the conversation is still warm.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
object Agent {

    private const val TAG = "AGENT"

    /**
     * How many consecutive blank STT responses we tolerate before giving
     * up and ending the conversation.
     *
     * Each `ERROR_NO_MATCH` from the recogniser counts as one blank, and
     * Android's STT typically times out after ~5 s of silence — so the
     * default of 30 gives the user a couple of minutes of "I'm thinking,
     * give me a moment" without Droidal bailing on them. The conversation
     * is normally ended explicitly by the LLM via the `endConversation`
     * tool (or the `[END_CONVERSATION]` marker), not by silence.
     */
    private const val MAX_BLANK_RETRIES = 30

    private val scope = MainScope()
    private val chatting = AtomicBoolean(false)

    /** True while a conversation is in flight — workers consult this. */
    fun isChatting(): Boolean = chatting.get()

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
        val context = Config.getContext()
        val store = LearningStore.get(context)
        // Resolve the active user with this precedence:
        //   1. Whatever the trigger said (typically a face-recognition
        //      match, or a news-scout primer addressed to a specific
        //      user). If we have an explicit name, trust it.
        //   2. The persistent debug override (`debug set user …`) — fills
        //      the "I know who's around but the camera hasn't told me"
        //      gap.
        //   3. UNKNOWN_USER.
        // The override is applied to a *copy* of [startConversation] so
        // downstream code (system-prompt builder, news lookup) sees the
        // resolved name rather than null.
        val incoming = startConversation.user?.takeIf { it.isNotBlank() }
        val override = SettingsRepository.get(context).currentUserOverride()
        val resolvedUser = incoming ?: override
        val effectiveStart = if (resolvedUser != null && resolvedUser != startConversation.user) {
            startConversation.copy(user = resolvedUser)
        } else {
            startConversation
        }
        val userId = LearningPaths.sanitize(resolvedUser ?: LearningPaths.UNKNOWN_USER)
        val sessionId = UUID.randomUUID().toString()
        LearningContext.set(userId, sessionId)
        try {
            val provider = LlmProviderFactory.current(context)
            val systemPrompt = store.systemPromptBlock(effectiveStart)
            var nextInput = if (startConversation.startedByUser) {
                startConversation.message
            } else {
                "Start a conversation with me!"
            }
            val streamingMode = SettingsRepository.get(context).streamingMode()
            Log.i(TAG, "Conversation starting on ${provider.displayName} (streaming=$streamingMode, user=$userId)")

            // Speak a "hang on while I load my brain" filler before the
            // (potentially multi-second) engine init kicks off, so the
            // user hears something while LiteRtLmEngineCache loads the
            // .litertlm file. The Say event queues into TextToSpeech and
            // plays asynchronously while newSession() blocks.
            val needsWarmup = provider.requiresWarmup()
            if (needsWarmup) Filler.sayLoadingBrain()

            val session = provider.newSession(systemPrompt, DroidalTools())
            // If the local engine had to fall back (e.g. GPU → CPU), warn
            // the user once so long responses don't look like a hang.
            com.prlancas.droidal.brain.llm.LiteRtLmEngineCache.takeWarning()?.let {
                EventBus.publishAsync(Say(it))
            }
            // Scope used to schedule per-turn "thinking" filler timers.
            // Inherits the current dispatcher so cancellation from the
            // streaming callback is cheap and synchronous.
            val turnScope = CoroutineScope(currentCoroutineContext())
            // The brain-load filler already covered the silence on turn
            // one — don't pile a second filler on top of it.
            var skipNextThinkingFiller = needsWarmup
            try {
                while (true) {
                    if (nextInput.isNotBlank()) {
                        store.recordTurn(userId, sessionId, LearningDatabase.ROLE_USER, nextInput)
                        ConversationLog.append(ConversationLog.Kind.LLM_REQUEST, nextInput)
                    }
                    val streamer = TtsStreamer(streamingMode)
                    val thinkingJob = if (skipNextThinkingFiller) {
                        skipNextThinkingFiller = false
                        null
                    } else {
                        Filler.scheduleThinking(turnScope)
                    }
                    DebugBus.setActivity(DebugActivityState.CALLING_LLM)
                    val reply = try {
                        session.send(nextInput) { delta ->
                            thinkingJob?.cancel()
                            streamer.feed(delta)
                        }
                    } finally {
                        thinkingJob?.cancel()
                    }
                    streamer.finishAndAwait()
                    Log.i(TAG, "Reply: $reply")
                    if (reply.isNotBlank()) {
                        val cleanedReply = reply.replace("[END_CONVERSATION]", "").trim()
                        store.recordTurn(
                            userId,
                            sessionId,
                            LearningDatabase.ROLE_ASSISTANT,
                            cleanedReply,
                        )
                        ConversationLog.append(ConversationLog.Kind.LLM_RESPONSE, cleanedReply)
                    }

                    // Two ways the model can signal the end of the
                    // conversation cleanly:
                    //
                    //   1. The `endConversation` tool — preferred, since
                    //      it works even when the model's wrapper format
                    //      strips raw text markers from the streaming
                    //      output.
                    //   2. The `[END_CONVERSATION]` marker in the reply
                    //      text — the streamer has already truncated
                    //      everything from the marker onwards, so the
                    //      farewell sentence preceding it has been
                    //      spoken.
                    //
                    // Either path stops the loop without trying to listen
                    // again.
                    if (LearningContext.wasEndRequested()) {
                        Log.i(TAG, "endConversation tool requested — ending conversation")
                        return
                    }
                    if (reply.contains("[END_CONVERSATION]")) {
                        Log.i(TAG, "[END_CONVERSATION] marker seen — ending conversation")
                        return
                    }

                    val followUp = listenWithRetry()
                    if (followUp.isNullOrBlank()) {
                        // We've exhausted MAX_BLANK_RETRIES consecutive
                        // blanks. The user has likely walked away — bail
                        // without further announcements.
                        Log.i(TAG, "Hit blank-STT retry limit ($MAX_BLANK_RETRIES) — ending conversation")
                        return
                    }
                    nextInput = followUp
                }
            } finally {
                session.close()
            }
        } catch (e: Exception) {
            // Speak a short, human-friendly summary; the full message
            // (which can be 500+ chars of JNI / parser stack trace for
            // LiteRT-LM tool-call grammar failures) only goes to logcat.
            Log.e(TAG, "Error in conversation: ${e.message}", e)
            val friendly = ConversationErrorMessages.friendly(e)
            EventBus.publishAsync(Say(friendly))
        } finally {
            chatting.set(false)
            LearningContext.clear()
            ReflectorWorker.enqueueOneShot(context, userId)
        }
    }

    /**
     * Listen for the user's next turn, retrying up to [MAX_BLANK_RETRIES]
     * times if STT returns null/blank.
     *
     * Delegates to [ConversationListenPolicy], which encapsulates the
     * "first retry says Pardon, the rest are silent, give up after N"
     * shaping so it can be unit-tested without Android.
     */
    private suspend fun listenWithRetry(): String? =
        ConversationListenPolicy.listenWithRetry(
            maxBlankRetries = MAX_BLANK_RETRIES,
            listen = { silent -> listenSuspend(silent = silent) },
            say = { text -> EventBus.publishAsync(Say(text)) },
        )

    /** Wraps [Listen.listenOnly] in a suspending call. */
    private suspend fun listenSuspend(silent: Boolean = false): String? {
        val deferred = CompletableDeferred<String?>()
        Listen.listenOnly(silent = silent) { reply -> deferred.complete(reply) }
        return deferred.await()
    }
}
