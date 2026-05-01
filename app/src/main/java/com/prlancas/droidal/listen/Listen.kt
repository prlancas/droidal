package com.prlancas.droidal.listen

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.prlancas.droidal.MainActivity
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.debug.DebugActivityState
import com.prlancas.droidal.debug.DebugBus
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import com.prlancas.droidal.event.events.StartConversation
import com.prlancas.droidal.settings.SettingsRepository
import com.prlancas.droidal.speech.SpeechToText

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
                // Only restart wake-word detection once the STT session has
                // fully finished; also clears the awake guard so the next
                // wake-word trigger can fire.
                wakeGuard.release()
                startWakeWordDetection()
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
        // while we're still speaking "yes?" / listening are silently
        // dropped rather than stacking up "yes yes yes" / "pardon pardon".
        if (!wakeGuard.tryAwaken()) {
            Log.w(TAG, "Wake-word triggered while already awake — ignoring")
            return
        }
        speakAndListen("yes?") { message ->
            Log.i(TAG, "message was $message")
            message?.let {
                EventBus.publishAsync(StartConversation(startedByUser = true, it))
            }
            // NB: wakeGuard is released from the STT onRecognitionComplete
            // listener (which also restarts wake-word detection), so there
            // is exactly one code path that ends the awake window.
        }
    }

    fun speakAndListen(reply: String, onComplete: ((text: String?) -> Unit)) {
        stopWakeWordDetection()

        EventBus.publishAsync(Say(reply) {
            Log.d(TAG, "TTS completed, starting speech-to-text")
            Handler(Looper.getMainLooper()).post {
                speechToText.startListening(onComplete = onComplete)
            }
        })
    }

    /**
     * Suspend-friendly version used by the chat agent. Identical semantics
     * to [speakAndListen] but kept separate for call-site clarity.
     */
    fun listenAndReplySuspend(reply: String, onComplete: ((text: String?) -> Unit)) {
        stopWakeWordDetection()

        EventBus.publishAsync(Say(reply) {
            Log.d("WAKE_WORD", "TTS completed, starting speech-to-text")
            Handler(Looper.getMainLooper()).post {
                speechToText.startListening(onComplete = onComplete)
            }
        })
    }

    /**
     * Listen-only entry-point used by streaming agents: TTS for the
     * model's reply has already been handled (incrementally, via
     * [com.prlancas.droidal.speech.TtsStreamer]), so all we need to do
     * is shut the wake-word listener down (free the mic) and start STT
     * immediately. The [SpeechToText] completion listener will restart
     * the wake word once recognition finishes.
     *
     * [silent] suppresses STT's built-in "Pardon" / "I didn't hear
     * anything" announcements; the agent uses this so a long retry
     * streak doesn't fill the room with apologies.
     */
    fun listenOnly(silent: Boolean = false, onComplete: ((text: String?) -> Unit)) {
        stopWakeWordDetection()
        Handler(Looper.getMainLooper()).post {
            speechToText.startListening(suppressErrorSpeech = silent, onComplete = onComplete)
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
}
