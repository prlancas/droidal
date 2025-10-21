package com.prlancas.droidal.wake

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import android.content.Context
import android.util.Log
import com.prlancas.droidal.MainActivity
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import com.prlancas.droidal.speech.SpeechToText
import java.util.Properties

class WakeWordDetection(val mainActivity: MainActivity) {

    private lateinit var porcupineManager: PorcupineManager
    private lateinit var speechToText: SpeechToText

    fun startWakeWordDetection(context: Context ) {
        val props  = context.assets.open("keys.properties").use {
            Properties().apply { load(it) }
        }
        
        // Initialize speech-to-text
        try {
            speechToText = SpeechToText(context)
        } catch (e: Exception) {
            Log.e("WAKE_WORD", "Error initializing SpeechToText: ${e.message}")
            return
        }
        
        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(props.getProperty("porcupine_key"))
                .setKeyword(Porcupine.BuiltInKeyword.TERMINATOR)
                .setSensitivity(0.7f)
                .build(
                    mainActivity.applicationContext
                ) {
                    // Stop wake word detection to free up microphone
                    Log.d("WAKE_WORD", "Stopping wake word detection to free microphone")
                    try {
                        porcupineManager.stop()
                    } catch (e: Exception) {
                        Log.e("WAKE_WORD", "Error stopping wake word detection: ${e.message}")
                    }

                    speechToText.setOnRecognitionCompleteListener {
                        Log.d("WAKE_WORD", "Speech recognition complete, restarting wake word detection")
                        try {
                            porcupineManager.start()
                        } catch (e: Exception) {
                            Log.e("WAKE_WORD", "Error restarting wake word detection: ${e.message}")
                        }
                    }

                    EventBus.blockPublish(Say("Yes?") {
                        Log.d("WAKE_WORD", "TTS completed, starting speech-to-text")
                        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                        mainHandler.post {
                            speechToText.startListening()
                        }
                        // Set up a callback to restart wake word detection after speech recognition

                    })
                }
            porcupineManager.start()
        } catch (e: PorcupineException) {
            Log.e("PORCUPINE_SERVICE", e.toString())
        }
    }
}