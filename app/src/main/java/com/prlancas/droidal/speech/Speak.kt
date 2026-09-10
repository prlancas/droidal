package com.prlancas.droidal.speech

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.debug.ConversationLog
import com.prlancas.droidal.debug.DebugActivityState
import com.prlancas.droidal.debug.DebugBus
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import com.prlancas.droidal.event.events.StopSpeaking
import com.prlancas.droidal.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.CountDownLatch

/**
 * Wrapper around Android's [TextToSpeech] that respects the user's TTS
 * source preference:
 *
 * - [SettingsRepository.TtsSource.ON_DEVICE] (default) — pick a voice that
 *   does not require a network connection. Keeps audio private and works
 *   offline.
 * - [SettingsRepository.TtsSource.GOOGLE_CLOUD] — prefer a network voice
 *   (typically higher fidelity on Google TTS).
 *
 * Call [applyVoicePreference] after the user toggles the setting to switch
 * voices without re-creating the engine.
 */
class Speak(private val ttobj: TextToSpeech) {

    private val ttsScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())

    companion object {
        private const val TAG = "TTS"
        private val activeUtterances = mutableMapOf<String, CountDownLatch>()
        private val utteranceCallbacks = mutableMapOf<String, () -> Unit>()

        @Volatile private var instance: Speak? = null

        fun setInstance(speak: Speak) {
            instance = speak
        }

        /**
         * Re-read [SettingsRepository.ttsSource] and pick an appropriate
         * voice. Safe to call from anywhere; a no-op if [Speak] hasn't been
         * initialised yet.
         */
        fun applyVoicePreference() {
            instance?.applyVoicePreference()
        }
    }

    init {
        setInstance(this)

        ttsScope.launch {
            val result = ttobj.setLanguage(Locale.UK)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language 'en-GB' is not available or missing data. Check TTS engine settings.")
            } else {
                Log.i(TAG, "Language set to en-GB successfully.")
            }

            applyVoicePreference()
            setupProgressListener()
            subscribeToSayEvents()
        }
        ttsScope.launch { subscribeToStopEvents() }
    }

    private suspend fun subscribeToStopEvents() {
        EventBus.subscribe<StopSpeaking> {
            Log.i(TAG, "StopSpeaking received — flushing TTS queue.")
            runCatching { ttobj.stop() }
            DebugBus.setActivity(DebugActivityState.IDLE)
            synchronized(activeUtterances) {
                activeUtterances.values.forEach { it.countDown() }
                activeUtterances.clear()
            }
            synchronized(utteranceCallbacks) { utteranceCallbacks.clear() }
        }
    }

    /**
     * Pick a voice for the current [SettingsRepository.ttsSource]. Falls
     * back gracefully if a matching voice isn't installed (we still speak
     * with whatever the engine defaults to).
     */
    fun applyVoicePreference() {
        val source = runCatching {
            SettingsRepository.get(Config.getContext()).ttsSource()
        }.getOrDefault(SettingsRepository.TtsSource.ON_DEVICE)

        val voices: Set<Voice> = runCatching { ttobj.voices }.getOrNull().orEmpty()
        if (voices.isEmpty()) {
            Log.w(TAG, "TextToSpeech reported no voices; cannot apply preference '$source'.")
            return
        }

        val english = voices.filter { it.locale.language == "en" }

        val preferred = when (source) {
            SettingsRepository.TtsSource.ON_DEVICE -> english
                .filter { !it.isNetworkConnectionRequired }
                .sortedBy { if (it.locale == Locale.UK) 0 else 1 }

            SettingsRepository.TtsSource.GOOGLE_CLOUD -> english
                .sortedBy { if (it.isNetworkConnectionRequired) 0 else 1 }
                .sortedBy { if (it.locale == Locale.UK) 0 else 1 }
        }

        val picked = preferred.firstOrNull()
        if (picked != null) {
            ttobj.voice = picked
            Log.i(
                TAG,
                "Using TTS voice '${picked.name}' " +
                    "(locale=${picked.locale}, network=${picked.isNetworkConnectionRequired}, " +
                    "source=$source)",
            )
        } else {
            Log.w(TAG, "No voice matched preference '$source'; keeping default.")
        }
    }

    private fun setupProgressListener() {
        ttobj.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "Started speaking utterance: $utteranceId")
                DebugBus.setActivity(DebugActivityState.SPEAKING)
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "Finished speaking utterance: $utteranceId")
                handleUtteranceCompletion(utteranceId)
            }

            @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, 0)"))
            override fun onError(utteranceId: String?) {
                onError(utteranceId, 0)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "Error speaking utterance: $utteranceId, Code: $errorCode")
                handleUtteranceCompletion(utteranceId)
            }
        })
    }

    private suspend fun subscribeToSayEvents() {
        EventBus.subscribe<Say> { event ->
            Log.i("Speak", "Say event received: ${event.sentence}")
            say(event.sentence, event.onComplete)
        }
    }

    private fun say(sentence: String, onComplete: (() -> Unit)? = null) {
        Log.i(TAG, "Saying: $sentence")
        ConversationLog.append(ConversationLog.Kind.SPOKE, sentence)
        val utteranceId = "utterance_${System.currentTimeMillis()}"

        val latch = CountDownLatch(1)
        synchronized(activeUtterances) {
            activeUtterances[utteranceId] = latch
        }

        if (onComplete != null) {
            synchronized(utteranceCallbacks) {
                utteranceCallbacks[utteranceId] = onComplete
            }
        }

        val result = ttobj.speak(sentence, TextToSpeech.QUEUE_ADD, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "Failed to start speech for utterance: $utteranceId")
            handleUtteranceCompletion(utteranceId)
        }
    }

    private fun handleUtteranceCompletion(utteranceId: String?) {
        utteranceId?.let { id ->
            synchronized(activeUtterances) {
                activeUtterances[id]?.countDown()
                activeUtterances.remove(id)
            }
            val callback = synchronized(utteranceCallbacks) {
                utteranceCallbacks.remove(id)
            }
            callback?.invoke()
        }
    }
}
