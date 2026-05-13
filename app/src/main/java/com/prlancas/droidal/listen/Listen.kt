package com.prlancas.droidal.listen

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.prlancas.droidal.MainActivity
import com.prlancas.droidal.brain.Agent
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.debug.DebugActivityState
import com.prlancas.droidal.debug.DebugBus
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.StartConversation
import com.prlancas.droidal.settings.SettingsRepository
import com.prlancas.droidal.speech.SpeechToText
import com.prlancas.droidal.status.GlobalStatus

object Listen {

    private const val TAG = "LISTEN"

    private lateinit var mainActivity: MainActivity
    private lateinit var porcupineManager: PorcupineManager
    private lateinit var speechToText: SpeechToText

    /**
     * Guard against wake-word callbacks firing multiple times before we've
     * finished processing the first one. See [WakeGuard].
     */
    private val wakeGuard = WakeGuard()

    fun init(mainActivity: MainActivity, context: Context) {
        this.mainActivity = mainActivity

        try {
            speechToText = SpeechToText(context)
            speechToText.setOnRecognitionCompleteListener {
                // Clear the awake guard so the next wake-word trigger
                // can fire. We restart wake-word detection here only
                // when the agent is no longer in a conversation —
                // otherwise the aggregator is about to fire another
                // STT session for the next segment / turn and we'd
                // pointlessly tear down + rebuild the mic between
                // segments. The conversation-end path (Agent finally
                // block → next listen returns null) lets STT complete
                // with isChatting()==false, and then we restart wake
                // word naturally.
                wakeGuard.release()
                if (!Agent.isChatting()) startWakeWordDetection()
            }
        } catch (e: Exception) {
            Log.e("WAKE_WORD", "Error initializing SpeechToText: ${e.message}")
            return
        }

        try {
            porcupineManager = buildPorcupineManager(context)
            startWakeWordDetection()
        } catch (e: PorcupineException) {
            Log.e("PORCUPINE_SERVICE", e.toString())
        }
    }

    /**
     * Rebuild the wake-word detector from the latest settings *while the
     * app is running*. Triggered by the wake-word settings page so a
     * change to the keyword or access key takes effect without
     * restarting the activity.
     *
     * Safe to call before [init] has run (no-op until then) and safe to
     * call concurrently with wake-word callbacks — the synchronized
     * block ensures we don't tear down a manager mid-trigger.
     */
    @Synchronized
    fun reloadWakeWord(context: Context) {
        if (!::porcupineManager.isInitialized) {
            Log.d(TAG, "reloadWakeWord called before init — ignoring")
            return
        }
        Log.i(TAG, "Reloading wake-word from settings")
        runCatching { porcupineManager.stop() }
            .onFailure { Log.w(TAG, "stop() during reload failed: ${it.message}") }
        runCatching { porcupineManager.delete() }
            .onFailure { Log.w(TAG, "delete() during reload failed: ${it.message}") }
        try {
            porcupineManager = buildPorcupineManager(context)
            // Don't restart detection if Droidal is currently in a
            // conversation (the mic is busy with STT) — the existing
            // SpeechToText completion listener will resume wake-word
            // detection once the conversation ends, picking up the
            // newly-built manager.
            if (!wakeGuard.isAwake()) {
                startWakeWordDetection()
            }
        } catch (e: PorcupineException) {
            Log.e(TAG, "Wake-word rebuild failed: $e")
        }
    }

    private fun buildPorcupineManager(context: Context): PorcupineManager {
        // Wake-word config is sourced from SettingsRepository so the user
        // can override both the access key (Picovoice console) and the
        // keyword (any of Porcupine.BuiltInKeyword) without rebuilding.
        // We fall back to the access key bundled in assets/keys.properties
        // — that's the dev-build default so existing installs keep working.
        val settings = SettingsRepository.get(context)
        val accessKey = settings.porcupineAccessKey()
            ?: Config.key("porcupine_key")
        val keyword = runCatching {
            Porcupine.BuiltInKeyword.valueOf(settings.wakeWord())
        }.getOrDefault(Porcupine.BuiltInKeyword.TERMINATOR)
        Log.i(TAG, "Building PorcupineManager with keyword=${keyword.name}")
        return PorcupineManager.Builder()
            .setAccessKey(accessKey)
            .setKeyword(keyword)
            .setSensitivity(0.7f)
            .build(context.applicationContext) {
                awaken()
            }
    }

    private fun awaken() {
        // Single-bit CAS via [WakeGuard] so repeated wake-word detections
        // while we're still acknowledging / listening are silently
        // dropped rather than stacking up "yes yes yes".
        if (!wakeGuard.tryAwaken()) {
            Log.w(TAG, "Wake-word triggered while already awake — ignoring")
            return
        }
        // The wake word's only job is to kick off a conversation. The
        // agent owns the verbal acknowledgement ("yes?") and the
        // patient first-listen — same pipeline as every follow-up
        // turn — so a thoughtful pause after the wake word doesn't
        // get cut off by a one-shot recogniser.
        //
        // We drop the wake-word listener here so it's not fighting the
        // agent for the mic; SpeechToText's completion listener will
        // restart it (and release the wake-guard) once the
        // conversation winds down.
        stopWakeWordDetection()
        EventBus.publishAsync(
            StartConversation(startedByUser = true, message = "", user = null),
        )
    }

    /**
     * Listen-only entry-point used by streaming agents: TTS for the
     * model's reply has already been handled (incrementally, via
     * [com.prlancas.droidal.speech.TtsStreamer]), so all we need to do
     * is shut the wake-word listener down (free the mic) and start STT
     * immediately. The [SpeechToText] completion listener will restart
     * the wake word once recognition finishes.
     *
     * STT is always silent now — the recogniser never speaks its own
     * apology; soft prompts are the agent's job (see
     * [com.prlancas.droidal.speech.Filler.sayStillThere]).
     */
    fun listenOnly(onComplete: ((text: String?) -> Unit)) {
        stopWakeWordDetection()
        Handler(Looper.getMainLooper()).post {
            speechToText.startListening(onComplete = onComplete)
        }
    }

    private fun stopWakeWordDetection() {
        Log.d("WAKE_WORD", "Stopping wake word detection to free microphone")
        try {
            porcupineManager.stop()
        } catch (e: Exception) {
            Log.e("WAKE_WORD", "Error stopping wake word detection: ${e.message}")
        }
    }

    private fun startWakeWordDetection() {
        Log.d("WAKE_WORD", "Starting wake word detection")
        try {
            porcupineManager.start()
            DebugBus.setActivity(DebugActivityState.LISTENING_FOR_WAKE_WORD)
        } catch (e: Exception) {
            Log.e("WAKE_WORD", "Error starting wake word detection: ${e.message}")
        }
    }

    /**
     * Resume wake-word detection from outside [Listen] — used by
     * `Agent.haveConversation`'s finally block after a conversation
     * ends, since the per-STT-completion listener intentionally skips
     * the restart while `Agent.isChatting()` is true. Safe to call if
     * Porcupine isn't initialised yet (no-op).
     */
    fun resumeWakeWordDetection() {
        if (!::porcupineManager.isInitialized) {
            Log.d(TAG, "resumeWakeWordDetection called before init — ignoring")
            return
        }
        startWakeWordDetection()
    }

    /**
     * Milliseconds since the recogniser last heard voice (RMS spike
     * over its threshold), or [Long.MAX_VALUE] when no voice has been
     * detected this listen yet. Used by
     * `ConversationListenPolicy.listenPatiently` to distinguish a
     * "user thinking mid-thought" pause from a "user has walked away"
     * silence.
     */
    fun msSinceLastVoice(): Long {
        if (!::speechToText.isInitialized) return Long.MAX_VALUE
        val last = speechToText.lastVoiceAtMs()
        if (last == 0L) return Long.MAX_VALUE
        return (System.currentTimeMillis() - last).coerceAtLeast(0L)
    }

    /**
     * Milliseconds since the camera last saw a face, or
     * [Long.MAX_VALUE] when no face has ever been seen this run (or
     * the camera processor isn't running). Companion to
     * [msSinceLastVoice] for the patient listen policy: a face that
     * was visible recently is a strong "user is still here" signal
     * even when they're quiet.
     */
    fun msSinceLastFaceSeen(): Long {
        val last = GlobalStatus.lastFaceSeenAtMs
        if (last == 0L) return Long.MAX_VALUE
        return (System.currentTimeMillis() - last).coerceAtLeast(0L)
    }
}
