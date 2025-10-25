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
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import com.prlancas.droidal.event.events.SendToLLM
import com.prlancas.droidal.speech.SpeechToText

object Listen {

    private lateinit var mainActivity: MainActivity
    private lateinit var porcupineManager: PorcupineManager
    private lateinit var speechToText: SpeechToText

    fun init(mainActivity: MainActivity, context: Context) {
        this.mainActivity = mainActivity



        // Initialize speech-to-text
        try {
            speechToText = SpeechToText(context)
        } catch (e: Exception) {
            Log.e("WAKE_WORD", "Error initializing SpeechToText: ${e.message}")
            return
        }

        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(Config.key("porcupine_key"))
                .setKeyword(Porcupine.BuiltInKeyword.TERMINATOR)
                .setSensitivity(0.7f)
                .build(
                    mainActivity.applicationContext
                ) {
                    awaken()
                }
            startWakeWordDetection()
        } catch (e: PorcupineException) {
            Log.e("PORCUPINE_SERVICE", e.toString())
        }
    }

    private fun awaken() {
        listenAndReply("yes?") { message ->
            Log.i("LISTEN", "message was $message")
            message?.let {
                EventBus.publishAsync(SendToLLM(it))
            }
        }
    }

    fun listenAndReply(reply: String, onComplete: ((text: String?) -> Unit)) {
        // Stop wake word detection to free up microphone
        stopWakeWordDetection()

        speechToText.setOnRecognitionCompleteListener {
            Log.d(
                "WAKE_WORD",
                "Speech recognition complete, restarting wake word detection"
            )
            try {
                porcupineManager.start()
            } catch (e: Exception) {
                Log.e("WAKE_WORD", "Error restarting wake word detection: ${e.message}")
            }
        }

        EventBus.publishAsync(Say(reply) {
            Log.d("WAKE_WORD", "TTS completed, starting speech-to-text")
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                speechToText.startListening(onComplete)
            }
        })
        Log.i("LISTEN", "listenAndReply returning")
    }

    /**
     * Suspend version of listenAndReply that uses EventBus.publishAsync() to avoid deadlocks.
     * This is the recommended method for use in coroutine contexts.
     */
    suspend fun listenAndReplySuspend(reply: String, onComplete: ((text: String?) -> Unit)) {
        // Stop wake word detection to free up microphone
        stopWakeWordDetection()

        speechToText.setOnRecognitionCompleteListener {
            Log.d(
                "WAKE_WORD",
                "Speech recognition complete, restarting wake word detection"
            )
            try {
                porcupineManager.start()
            } catch (e: Exception) {
                Log.e("WAKE_WORD", "Error restarting wake word detection: ${e.message}")
            }
        }

        EventBus.publishAsync(Say(reply) {
            Log.d("WAKE_WORD", "TTS completed, starting speech-to-text")
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                speechToText.startListening(onComplete)
            }
        })
        Log.i("LISTEN", "listenAndReplySuspend returning")
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
        try {
            porcupineManager.start()
        } catch (e: Exception) {
            Log.e("WAKE_WORD", "Error starting wake word detection: ${e.message}")
        }
    }
}
