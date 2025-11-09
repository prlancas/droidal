package com.prlancas.droidal.speech

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.CountDownLatch

// FIX: Removed @OptIn for DelicateCoroutinesApi and ExperimentalCoroutinesApi as they are no longer needed with this new structure.
class Speak(private val ttobj: TextToSpeech) {

    // FIX 1: Create a dedicated, lifecycle-aware coroutine scope.
    // Use SupervisorJob so if one child coroutine fails, it doesn't cancel the whole scope.
    // All TTS operations will run on a single background thread to ensure safety.
    private val ttsScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())

    companion object {
        private val activeUtterances = mutableMapOf<String, CountDownLatch>()
        private val utteranceCallbacks = mutableMapOf<String, () -> Unit>()
        @Volatile
        private var instance: Speak? = null

        fun setInstance(speak: Speak) {
            instance = speak
        }
    }

    init {
        setInstance(this)

        ttsScope.launch {
            val result = ttobj.setLanguage(Locale.forLanguageTag("en-GB"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "Language 'en-GB' is not available or missing data. Check TTS engine settings.")
                // You could add fallback logic here if needed, e.g., to Locale.US
            } else {
                Log.i("TTS", "Language set to en-GB successfully.")
            }

            setupProgressListener()

            subscribeToSayEvents()
        }
    }

    private fun setupProgressListener() {
        ttobj.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d("TTS", "Started speaking utterance: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.d("TTS", "Finished speaking utterance: $utteranceId")
                handleUtteranceCompletion(utteranceId)
            }

            @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, 0)"))
            override fun onError(utteranceId: String?) {
                onError(utteranceId, 0)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e("TTS", "Error speaking utterance: $utteranceId, Code: $errorCode")
                handleUtteranceCompletion(utteranceId) // Still complete to unblock waiting code
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
        Log.i("TTS", "Saying: $sentence")
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
            Log.e("TTS", "Failed to start speech for utterance: $utteranceId")
            // If TTS failed to start, clean up immediately.
            handleUtteranceCompletion(utteranceId)
        }
    }

    private fun handleUtteranceCompletion(utteranceId: String?) {
        utteranceId?.let { id ->
            synchronized(activeUtterances) {
                activeUtterances[id]?.countDown()
                activeUtterances.remove(id)
            }
            // Safely invoke and remove the callback.
            val callback = synchronized(utteranceCallbacks) {
                utteranceCallbacks.remove(id)
            }
            callback?.invoke()
        }
    }

    // This should be called from your Activity/Fragment's onDestroy or onCleared.
    fun shutdown() {
        Log.i("Speak", "Shutting down Speak class and TTS engine.")
        ttsScope.cancel() // Cancel all coroutines started in this scope.
        ttobj.stop()
        ttobj.shutdown()
        instance = null
    }
}