package com.prlancas.droidal.speech

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import java.util.Locale
import java.util.concurrent.CountDownLatch

@OptIn(DelicateCoroutinesApi::class)
class Speak(val ttobj: TextToSpeech) {
    private val scope = MainScope()
    
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
        // Set this instance as the global instance
        setInstance(this)
        
        // Set up TTS utterance progress listener
        ttobj.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // TTS started
            }
            
            override fun onDone(utteranceId: String?) {
                // TTS completed - signal completion
                utteranceId?.let { id ->
                    synchronized(activeUtterances) {
                        activeUtterances[id]?.countDown()
                        activeUtterances.remove(id)
                    }
                    // Call callback if it exists
                    synchronized(utteranceCallbacks) {
                        utteranceCallbacks[id]?.invoke()
                        utteranceCallbacks.remove(id)
                    }
                }
            }

            @Deprecated("Deprecated in Java",
                ReplaceWith("onError(utteranceId = utteranceId, errorCode = 0)")
            )
            override fun onError(utteranceId: String?) {
                onError(utteranceId = utteranceId, errorCode = 0)
            }
            
            override fun onError(utteranceId: String?, errorCode: Int) {
                // TTS error - still signal completion
                utteranceId?.let { id ->
                    synchronized(activeUtterances) {
                        activeUtterances[id]?.countDown()
                        activeUtterances.remove(id)
                    }
                    // Call callback even on error
                    synchronized(utteranceCallbacks) {
                        utteranceCallbacks[id]?.invoke()
                        utteranceCallbacks.remove(id)
                    }
                }
            }
        })
        
        scope.launch(newSingleThreadContext("MyOwnThread")) {
            EventBus.subscribe<Say> {
                say(it.sentence, it.onComplete)
            }
        }
    }

    private fun say(sentence: String, onComplete: (() -> Unit)? = null) {
        ttobj.language = Locale.UK
        
        // Generate unique utterance ID
        val utteranceId = "utterance_${System.currentTimeMillis()}_${sentence.hashCode()}"
        
        // Create latch for this utterance
        val latch = CountDownLatch(1)
        synchronized(activeUtterances) {
            activeUtterances[utteranceId] = latch
        }
        
        // Store callback for this utterance
        if (onComplete != null) {
            synchronized(utteranceCallbacks) {
                utteranceCallbacks[utteranceId] = onComplete
            }
        }
        
        // Start TTS
        val result = ttobj.speak(sentence, TextToSpeech.QUEUE_ADD, null, utteranceId)
        
        if (result != TextToSpeech.SUCCESS) {
            // If TTS failed to start, signal completion immediately
            synchronized(activeUtterances) {
                activeUtterances[utteranceId]?.countDown()
                activeUtterances.remove(utteranceId)
            }
            // Call callback immediately if TTS failed
            onComplete?.invoke()
        }
    }
    
    // Method to wait for all active TTS to complete
    fun waitForCompletion() {
        val latches: List<CountDownLatch>
        synchronized(activeUtterances) {
            latches = activeUtterances.values.toList()
        }
        
        latches.forEach { latch ->
            try {
                latch.await()
            } catch (e: InterruptedException) {
                // Thread was interrupted
            }
        }
    }
}