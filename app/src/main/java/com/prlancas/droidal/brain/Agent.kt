package com.prlancas.droidal.brain

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.prlancas.droidal.brain.llm.LiteRtLmEngineCache
import com.prlancas.droidal.brain.llm.LlmProviderFactory
import com.prlancas.droidal.brain.tools.DroidalTools
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.debug.ConversationLog
import com.prlancas.droidal.debug.DebugActivityState
import com.prlancas.droidal.debug.DebugBus
import com.prlancas.droidal.debug.VerboseLog
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Expression
import com.prlancas.droidal.event.events.Say
import com.prlancas.droidal.event.events.SetExpression
import com.prlancas.droidal.event.events.StartConversation
import com.prlancas.droidal.event.events.StopSpeaking
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.withTimeoutOrNull
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

    private val scope = MainScope()
    private val chatting = AtomicBoolean(false)
    private var activeConversationJob: Job? = null

    /** True while a conversation is in flight — workers consult this. */
    fun isChatting(): Boolean = chatting.get()

    init {
        scope.launch(newFixedThreadPoolContext(10, "LLMThreads")) {
            EventBus.subscribe<StartConversation> { event ->
                if (chatting.compareAndSet(false, true)) {
                    activeConversationJob = CoroutineScope(Dispatchers.Default).launch {
                        try {
                            haveConversation(event)
                        } finally {
                            activeConversationJob = null
                        }
                    }
                }
            }
        }
    }

    /**
     * Force-stop any active conversation, flush TTS, and return to
     * wake-word listening. Used as a safety "reset" from the debug menu.
     */
    fun forceStop() {
        Log.i(TAG, "Force stopping conversation...")
        activeConversationJob?.cancel()
        // If there was no activeJob but chatting was somehow true,
        // flip it anyway to unblock the StartConversation listener.
        chatting.set(false)
        EventBus.publishAsync(StopSpeaking)
        // Resume the wake loop in case the finally block didn't run
        // (e.g. if the cancel call itself raced with a crash).
        Listen.resumeWakeWordDetection()
        DebugBus.setActivity(DebugActivityState.IDLE)
    }

    /** Dummy method to trigger initialization. */
    fun touch() {
        // No-op
    }

    suspend fun haveConversation(startConversation: StartConversation) {
        val context = Config.getContext()
        val store = LearningStore.get(context)
        val (effectiveStart, userId) = resolveStart(context, startConversation)
        val sessionId = UUID.randomUUID().toString()
        LearningContext.set(userId, sessionId)
        // Open the eyes (SLEEP -> NORMAL) while talking; the camera keeps
        // steering the gaze via Look. The finally block sleeps them again.
        EventBus.publishAsync(SetExpression(Expression.NORMAL))
        try {
            val provider = LlmProviderFactory.current(context)
            val systemPrompt = store.systemPromptBlock(effectiveStart)
            // Wake-word triggers now publish StartConversation with a
            // blank message — the agent owns the verbal "yes?" + first
            // patient listen so the same retry / face-aware policy
            // applies to the very first turn as to every follow-up.
            val isWakeWordTrigger =
                startConversation.startedByUser && startConversation.message.isBlank()
            if (isWakeWordTrigger) {
                // Fire the acknowledgement immediately and don't wait
                // for it — TTS queues with QUEUE_ADD and STT only
                // starts after listenOneTurn() runs, so "yes?" gets
                // out of the way before the mic opens. Running the
                // engine warm-up in parallel with the spoken greeting
                // also masks any GPU-fallback latency.
                Filler.sayAcknowledged()
            }

            var nextInput = when {
                isWakeWordTrigger -> ""
                startConversation.startedByUser -> startConversation.message
                else -> "Start a conversation with me!"
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

            logToolCatalogue(provider.displayName)

            val session = provider.newSession(systemPrompt, DroidalTools())
            // ONE try/finally wraps everything from here on so the
            // session is ALWAYS closed — even on the wake-word
            // "no utterance heard" early return below. A leaked
            // session corrupts the LiteRT-LM engine's "only one
            // session at a time" slot and makes the very next
            // conversation throw FAILED_PRECONDITION.
            try {
                // If the local engine had to fall back (e.g. GPU →
                // CPU), warn the user once so long responses don't
                // look like a hang.
                LiteRtLmEngineCache.takeWarning()?.let {
                    EventBus.publishAsync(Say(it))
                }
                // Scope used to schedule per-turn "thinking" filler
                // timers. Inherits the current dispatcher so
                // cancellation from the streaming callback is cheap
                // and synchronous.
                val turnScope = CoroutineScope(currentCoroutineContext())
                // The brain-load filler already covered the silence
                // on turn one — don't pile a second filler on top of
                // it.
                var skipNextThinkingFiller = needsWarmup

                if (isWakeWordTrigger) {
                    // The acknowledgement was queued before warm-up,
                    // so by the time we get here it has been (or is
                    // being) spoken. Patient-listen for the user's
                    // actual first utterance — same policy as every
                    // other turn, so a thoughtful pause after "yes?"
                    // is honoured.
                    val firstUtterance = listenOneTurn()
                    if (firstUtterance.isNullOrBlank()) {
                        Log.i(
                            TAG,
                            "Wake word triggered but no utterance heard — ending conversation",
                        )
                        return
                    }
                    nextInput = firstUtterance
                }

                while (true) {
                    if (nextInput.isNotBlank()) {
                        // ConversationLog is the in-memory debug ring
                        // buffer — write the request immediately so it
                        // shows up in the debug UI even if `send`
                        // crashes. The persistent learning DB record
                        // is deferred until after `send` returns to
                        // avoid orphan "(1 turns)" rows in RECENT
                        // CONVERSATIONS when prefill fails.
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
                        withTimeoutOrNull(60_000) {
                            session.send(nextInput) { delta ->
                                thinkingJob?.cancel()
                                streamer.feed(delta)
                            }
                        } ?: "I'm sorry, I timed out waiting for a reply."
                    } finally {
                        thinkingJob?.cancel()
                    }
                    // The user turn is only persisted once the LLM has
                    // actually replied — otherwise a context-overflow
                    // / parser failure leaves an orphan "(1 turns)"
                    // row that bloats every future system prompt.
                    if (nextInput.isNotBlank()) {
                        store.recordTurn(userId, sessionId, LearningDatabase.ROLE_USER, nextInput)
                    }
                    // Wait for the spoken reply to finish draining. Cap the
                    // wait at 30 seconds so a lost utterance completion
                    // doesn't hang the agent forever.
                    withTimeoutOrNull(30_000) {
                        streamer.finishAndAwait()
                    } ?: Log.w(TAG, "TtsStreamer timed out waiting for audio to drain")

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

                    val followUp = listenOneTurn()
                    if (followUp.isNullOrBlank()) {
                        // The patient listen policy gave up — the user
                        // has either walked away or stayed quiet
                        // through the soft-prompt extension. End the
                        // conversation gracefully.
                        Log.i(TAG, "Patient listen returned blank — ending conversation")
                        return
                    }
                    nextInput = followUp
                }
            } finally {
                runCatching { session.close() }.onFailure {
                    Log.w(TAG, "Failed to close session: ${it.message}")
                }
            }
        } catch (e: Exception) {
            // Speak a short, human-friendly summary; the full message
            // (which can be 500+ chars of JNI / parser stack trace for
            // LiteRT-LM tool-call grammar failures) only goes to logcat.
            Log.e(TAG, "Error in conversation: ${e.message}", e)
            logConversationError(e)
            // A LiteRT-LM JNI error (context overflow, parser failure)
            // can leave the native engine's single-session slot in an
            // unrecoverable state — `conversation.close()` doesn't
            // always free it. Drop the cached engine so the next
            // conversation rebuilds it cleanly instead of throwing
            // `FAILED_PRECONDITION: A session already exists`.
            if (e is LiteRtLmJniException) {
                Log.w(TAG, "LiteRT-LM JNI failure — invalidating engine cache")
                LiteRtLmEngineCache.invalidate()
            }
            val friendly = ConversationErrorMessages.friendly(e)
            EventBus.publishAsync(Say(friendly))
        } finally {
            chatting.set(false)
            LearningContext.clear()
            // Conversation over — close the eyes again (back to sleep).
            EventBus.publishAsync(SetExpression(Expression.SLEEP))
            // The per-STT-completion listener in Listen.init skipped
            // the wake-word restart while `chatting` was true, so we
            // resume detection here once the conversation is fully
            // wound down.
            Listen.resumeWakeWordDetection()
            ReflectorWorker.enqueueOneShot(context, userId)
        }
    }

    /**
     * Listen for the user's next *logical* turn — patient, multi-
     * segment, soft-prompt-on-real-silence.
     *
     * Built from two pure policies that are unit-tested without
     * Android:
     *
     *  - [ConversationListenPolicy.listenPatiently] handles the dead-
     *    air half: silently restart STT on any blank, only nudge with
     *    [Filler.sayStillThere] after a wall-clock budget elapses and
     *    *either* voice was heard recently or a face is currently
     *    visible (so a quiet user looking at Droidal is treated as
     *    "still here, thinking" rather than "gone"). Gives up
     *    gracefully if both signals are stale.
     *  - [ListenAggregator.listenOneTurn] handles the split-sentence
     *    half: after the first non-blank segment, briefly tail-listen
     *    for a continuation and concatenate so "Tell me about… (3 s)
     *    …the weather" reaches the LLM as one coherent turn.
     */
    private suspend fun listenOneTurn(): String? =
        ListenAggregator.listenOneTurn(
            // jarring.
            firstSegment = {
                ConversationListenPolicy.listenPatiently(
                    listen = { quietRestart -> listenSuspend(quietRestart) },
                    voiceHeardWithinMs = { Listen.msSinceLastVoice() },
                    faceVisibleWithinMs = { Listen.msSinceLastFaceSeen() },
                    softPrompt = { Filler.sayStillThere() },
                )
            },
        ) { _ -> listenSuspend(quietRestart = true) }

    private data class ResolvedStart(val start: StartConversation, val userId: String)

    /**
     * Resolve the active user for [startConversation] with this precedence:
     *   1. Whatever the trigger said (typically a face-recognition match, or
     *      a news-scout primer addressed to a specific user).
     *   2. The persistent debug override (`debug set user …`).
     *   3. UNKNOWN_USER.
     * The resolved name is applied to a *copy* of [startConversation] so
     * downstream code (system-prompt builder, news lookup) sees it rather
     * than null.
     */
    private fun resolveStart(context: Context, startConversation: StartConversation): ResolvedStart {
        val incoming = startConversation.user?.takeIf { it.isNotBlank() }
        val override = SettingsRepository.get(context).currentUserOverride()
        val resolvedUser = incoming ?: override
        val effectiveStart = if ((resolvedUser != null) && (resolvedUser != startConversation.user)) {
            startConversation.copy(user = resolvedUser)
        } else {
            startConversation
        }
        val userId = LearningPaths.sanitize(resolvedUser ?: LearningPaths.UNKNOWN_USER)
        return ResolvedStart(effectiveStart, userId)
    }

    /**
     * Surface the tool surface the model is being given so a failed tool
     * call in the debug log can be compared against "what exists". The
     * compact name list goes to the in-memory conversation log; the full
     * name+description listing goes to logcat (verbose) so the debug ring
     * buffer stays small.
     */
    private fun logToolCatalogue(providerName: String) {
        ConversationLog.append(
            ConversationLog.Kind.INFO,
            "Tools available (${DroidalTools.catalogue().size}): ${DroidalTools.toolNames()}",
        )
        VerboseLog.logProviderWire(providerName, "tool-catalogue", DroidalTools.catalogueDescription())
    }

    /**
     * Mirror a conversation-loop failure into the on-device conversation log
     * so it's visible in the debug menu (not just logcat). LiteRT-LM
     * tool-call grammar failures usually name the offending function in the
     * exception message, which the reader can compare against the "Tools
     * available" line logged at session start.
     */
    private fun logConversationError(e: Exception) {
        val detail = e.message?.let { ": $it" }.orEmpty()
        val hint = if (e is LiteRtLmJniException) {
            " (often a tool-call / grammar failure — compare with 'Tools available' above)"
        } else {
            ""
        }
        ConversationLog.append(
            ConversationLog.Kind.TOOL_RESULT,
            "${e.javaClass.simpleName}$detail$hint",
        )
    }

    /**
     * Wraps [Listen.listenOnly] in a suspending call. STT is always
     * silent now — the recogniser never speaks its own apology;
     * verbal nudges come from [Filler.sayStillThere].
     *
     * @param quietRestart forwards to
     *   [Listen.listenOnly]'s `quietRestart` flag — see
     *   [ConversationListenPolicy.listenPatiently] for when this
     *   should be `true`.
     */
    private suspend fun listenSuspend(quietRestart: Boolean = false): String? {
        val deferred = CompletableDeferred<String?>()
        Listen.listenOnly(quietRestart = quietRestart) { reply ->
            deferred.complete(reply)
        }
        return deferred.await()
    }
}
