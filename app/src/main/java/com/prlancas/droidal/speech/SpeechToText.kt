package com.prlancas.droidal.speech

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.prlancas.droidal.debug.DebugHandle
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import java.util.Locale

class SpeechToText(private val context: Context) {
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var timeoutHandler: android.os.Handler? = null
    private val TIMEOUT_DURATION = 5000L
    private var retryCount = 0
    private var startTime = 0L
    private var onRecognitionCompleteListener: (() -> Unit)? = null
    
    fun startListening( onComplete: ((text: String?) -> Unit)) {
        if (isListening) {
            Log.d("SPEECH_TO_TEXT", "Already listening, ignoring request")
            onComplete.invoke(null)
            return
        }
        
        // Reset retry count for new listening session
        retryCount = 0
        
        // Check if speech recognition is available
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e("SPEECH_TO_TEXT", "Speech recognition is not available on this device")
            onComplete.invoke(null)
            return
        }
        
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)

            // Mute notification sounds
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    Log.d("SPEECH_TO_TEXT", "Ready for speech - listening should start now")
                    isListening = true
                }
                
                override fun onBeginningOfSpeech() {
                    Log.d("SPEECH_TO_TEXT", "Beginning of speech detected - user is speaking")
                    audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, originalVolume, 0)
                }
                
                override fun onRmsChanged(rmsdB: Float) {
                    // Log volume changes to see if audio is being detected
                    if (rmsdB > 0) {
                        Log.d("SPEECH_TO_TEXT", "Audio level: $rmsdB dB")
                    }
                }
                
                override fun onBufferReceived(buffer: ByteArray?) {
                    Log.d("SPEECH_TO_TEXT", "Audio buffer received - size: ${buffer?.size}")
                }
                
                override fun onEndOfSpeech() {
                    Log.d("SPEECH_TO_TEXT", "End of speech detected - user stopped speaking")
                }
                
                override fun onError(error: Int) {
                    audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, originalVolume, 0)
                    val currentTime = System.currentTimeMillis()
                    val elapsedTime = currentTime - startTime
                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "Pardon"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                        else -> "Unknown error: $error"
                    }
                    Log.e("SPEECH_TO_TEXT", "Recognition error: $errorMessage (code: $error) after ${elapsedTime}ms")
                    
                    // Speak back error message
                    EventBus.publishAsync(Say(errorMessage))
                    
                    isListening = false
                    onComplete.invoke(null)
                    // Notify that recognition is complete (even on error)
                    onRecognitionCompleteListener?.invoke()
                }
                
                override fun onResults(results: android.os.Bundle?) {
                    isListening = false
                    audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, originalVolume, 0)
                    val currentTime = System.currentTimeMillis()
                    val elapsedTime = currentTime - startTime
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val recognizedText = matches[0]
                        Log.i("SPEECH_TO_TEXT", "Recognized text: $recognizedText after ${elapsedTime}ms")
                        
                        // Speak back what was heard using TTS
                        if (DebugHandle.echoBackEnabled) {
                            EventBus.publishAsync(Say("You said: $recognizedText"))
                        }

                        if (recognizedText.startsWith("debug", ignoreCase = true)) {
                                DebugHandle.debugCommand(recognizedText)
                            } else {
                                // Send to LLM for processing
//                                EventBus.blockPublish(SendToLLM(recognizedText))
                            Log.i("LISTEN", "message was $recognizedText")
                                onComplete.invoke(recognizedText)
                            }

                        retryCount = 0 // Reset retry count on successful recognition
                    } else {
                        Log.w("SPEECH_TO_TEXT", "No speech recognized after ${elapsedTime}ms")
                        // Speak back that nothing was heard
                        EventBus.publishAsync(Say("I didn't hear anything"))
                    }
                    isListening = false
                    
                    // Notify that recognition is complete
                    onRecognitionCompleteListener?.invoke()
                }
                
                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        Log.d("SPEECH_TO_TEXT", "Partial result: ${matches[0]}")
                    }
                }
                
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {
                    Log.d("SPEECH_TO_TEXT", "Event: $eventType")
                }
            })
            
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.UK)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening...")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Remove aggressive silence detection - let it use defaults
                // putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000)
                // putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 8000)
                // putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000)
            }
            
            startTime = System.currentTimeMillis()
            speechRecognizer?.startListening(intent)
            Log.d("SPEECH_TO_TEXT", "Started listening for speech at ${java.text.SimpleDateFormat("HH:mm:ss.SSS").format(java.util.Date(startTime))}")
            
            // Set up timeout
            timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
            timeoutHandler?.postDelayed({
                if (isListening) {
                    Log.w("SPEECH_TO_TEXT", "Speech recognition timeout, stopping...")
                    stopListening()
                    
                    // Speak back timeout message
//                    EventBus.blockPublish(Say("Speech recognition timed out"))
                    
                    onComplete.invoke(null)
                    // Notify that recognition is complete (timeout)
                    onRecognitionCompleteListener?.invoke()
                }
            }, TIMEOUT_DURATION)
            
        } catch (e: Exception) {
            Log.e("SPEECH_TO_TEXT", "Error starting speech recognition: ${e.message}")
            isListening = false
        }
    }
    
    fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
        timeoutHandler?.removeCallbacksAndMessages(null)
        timeoutHandler = null
        Log.d("SPEECH_TO_TEXT", "Stopped listening")
    }
    
    fun setOnRecognitionCompleteListener(listener: () -> Unit) {
        onRecognitionCompleteListener = listener
    }
    
    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        isListening = false
        timeoutHandler?.removeCallbacksAndMessages(null)
        timeoutHandler = null
        onRecognitionCompleteListener = null
        Log.d("SPEECH_TO_TEXT", "Speech recognizer destroyed")
    }
}
