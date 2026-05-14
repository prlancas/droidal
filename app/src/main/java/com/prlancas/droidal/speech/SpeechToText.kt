package com.prlancas.droidal.speech

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.debug.ConversationLog
import com.prlancas.droidal.debug.DebugActivityState
import com.prlancas.droidal.debug.DebugBus
import com.prlancas.droidal.debug.DebugHandle
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import java.util.Locale

/**
 * Why this class never speaks its own apology:
 *
 * Earlier versions emitted `Say("Pardon")` / `Say("I didn't hear anything")`
 * directly from the recogniser callbacks. That fought the patient listen
 * policy in [com.prlancas.droidal.brain.ConversationListenPolicy] (which
 * is supposed to silently restart STT on a thoughtful pause and only
 * nudge with a `Filler.sayStillThere()` after a long wall-clock budget),
 * and worse, the wake-word path produced a misleading "I didn't hear
 * anything" even when the recogniser HAD heard the user but lost the
 * transcription on the way to `onResults`. All verbal feedback now lives
 * in the agent / Filler; this class is a pure transport.
 */

class SpeechToText(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private val listenLock = ListenLock()
    private var timeoutHandler: android.os.Handler? = null

    /**
     * Hard wall-clock guard against a stuck recogniser. Was 5 s but
     * that cut off thoughtful pauses; the patient pause extras below
     * plus the wall-clock policy in [com.prlancas.droidal.brain.ConversationListenPolicy]
     * are what actually shape the user-visible silence tolerance — this
     * just stops a wedged recogniser leaking the mic forever.
     */
    private val timeoutDurationMs = 15_000L
    private var retryCount = 0
    private var startTime = 0L
    private var onRecognitionCompleteListener: (() -> Unit)? = null

    /**
     * Wall-clock timestamp of the last RMS spike heard (ms since boot).
     * `0L` means "no voice activity since this session started". Cheap
     * VAD-ish signal used by the listen policy to distinguish "user
     * paused mid-thought" (recent spike) from "user has walked away"
     * (long-quiet mic).
     */
    @Volatile private var lastVoiceAtMs: Long = 0L

    /** RMS dB threshold above which we consider the mic to have heard
     *  voice. Picked low so soft speech and breathing register, but high
     *  enough that a quiet room with the mic gain cranked up doesn't
     *  trip on noise. Tweak alongside [onRmsChanged]. */
    private val voiceRmsThreshold = 1.5f

    /**
     * Latest non-blank partial transcription seen this session. Used as
     * a fallback when [onResults] fires with an empty matches list — a
     * real failure mode on Samsung's Bixby-backed recogniser, where the
     * user is clearly visible (debug overlay shows partial text) but
     * the recogniser nevertheless finalises empty. We'd rather hand a
     * best-effort partial to the LLM than make the user repeat
     * themselves.
     */
    @Volatile private var lastPartialText: String = ""

    /** Read the timestamp of the last RMS spike. `0L` if none yet. */
    fun lastVoiceAtMs(): Long = lastVoiceAtMs

    /** Exposed for tests so unit tests can verify the double-listen guard
     *  without spinning up a real Android SpeechRecognizer.
     */
    internal fun isListeningForTest(): Boolean = listenLock.isListening()

    /**
     * Listen for the next utterance.
     *
     * Always silent — the recogniser never speaks its own
     * apology / "Pardon" / "I didn't hear anything". All verbal
     * feedback is the caller's responsibility (see
     * [com.prlancas.droidal.brain.ConversationListenPolicy] for the
     * patient retry policy and [com.prlancas.droidal.speech.Filler]
     * for the soft prompts).
     *
     * @param quietRestart when `true`, suppress the system "I'm
     *   listening" notification beep and extend the end-of-speech
     *   silence-length timeouts by [QUIET_RESTART_TIMEOUT_FACTOR].
     *   The first listen after a wake word is the only place the user
     *   needs the cue — every subsequent restart inside a single
     *   conversation should be quiet so the loop doesn't sound like
     *   a stuck doorbell, and the extra silence headroom is what stops
     *   the recogniser closing the mic before the user has started
     *   speaking. Default `false` so the wake-word path keeps its
     *   original cue.
     *
     * Re-entrant calls (a second `startListening` while a recognition
     * session is already in flight) are silently dropped — [onComplete]
     * is invoked with `null` so the caller's suspend doesn't hang, and
     * no second SpeechRecognizer is created. This protects the mic from
     * "double listen" — the agent's retry loop and a stray wake-word
     * trigger can otherwise race.
     */
    fun startListening(
        quietRestart: Boolean = false,
        onComplete: ((text: String?) -> Unit),
    ) {
        if (!listenLock.tryStart()) {
            Log.d("LISTEN", "Already listening, ignoring request")
            onComplete.invoke(null)
            return
        }

        // Reset retry count for new listening session
        retryCount = 0

        // Check if speech recognition is available
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e("LISTEN", "Speech recognition is not available on this device")
            // Must release the lock we just claimed via tryStart() — otherwise
            // every subsequent startListening() call would fail with "already
            // listening" forever.
            listenLock.release()
            onComplete.invoke(null)
            return
        }
        
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            // Mute the start/stop beep when:
            //  - the user has turned off the global beep, OR
            //  - this listen is a loop restart (`quietRestart`) so the
            //    user isn't pestered every time the patient policy
            //    silently re-opens the mic.
            if (!Config.beepWhenListening() || quietRestart) {
                audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
            }

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    Log.d("LISTEN", "Ready for speech - listening should start now")
                    DebugBus.setActivity(DebugActivityState.LISTENING_TO_USER)
                    DebugBus.setPartialSpeech("")
                }

                override fun onBeginningOfSpeech() {
                    Log.d("LISTEN", "Beginning of speech detected - user is speaking")
                    // In quietRestart mode we leave the notification
                    // stream muted until the recogniser finishes — that
                    // way the *stop* beep is silenced too, not just
                    // the start one. Outside quietRestart, restore the
                    // user's notification volume as soon as voice is
                    // detected so subsequent system sounds aren't
                    // accidentally silenced.
                    if (!quietRestart) {
                        audioManager.setStreamVolume(
                            AudioManager.STREAM_NOTIFICATION,
                            originalVolume,
                            0,
                        )
                    }
                }
                
                override fun onRmsChanged(rmsdB: Float) {
                    if (rmsdB > 0) {
                        Log.d("DETAILED_LISTEN", "Audio level: $rmsdB dB")
                    }
                    if (rmsdB >= voiceRmsThreshold) {
                        lastVoiceAtMs = System.currentTimeMillis()
                    }
                }
                
                override fun onBufferReceived(buffer: ByteArray?) {
                    Log.d("LISTEN", "Audio buffer received - size: ${buffer?.size}")
                }
                
                override fun onEndOfSpeech() {
                    Log.d("LISTEN", "End of speech detected - user stopped speaking")
                }
                
                override fun onError(error: Int) {
                    DebugBus.clearPartialSpeech()
                    audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, originalVolume, 0)
                    val elapsedTime = System.currentTimeMillis() - startTime
                    val errorName = errorName(error)
                    Log.e("LISTEN", "Recognition error: $errorName (code: $error) after ${elapsedTime}ms")

                    // If the recogniser errored AFTER actually transcribing
                    // partial text (Samsung sometimes finalises ERROR_NO_MATCH
                    // with a populated partial-results history), salvage the
                    // partial as the result — handing "what's the weather"
                    // to the LLM is far better than telling the user we
                    // didn't hear them when we obviously did.
                    val salvaged = lastPartialText.takeIf { it.isNotBlank() }
                    if (salvaged != null) {
                        Log.i("LISTEN", "Salvaged partial after $errorName: \"$salvaged\"")
                        ConversationLog.append(ConversationLog.Kind.USER_SAID, salvaged)
                        listenLock.release()
                        deliverResult(salvaged, onComplete)
                    } else {
                        listenLock.release()
                        onComplete.invoke(null)
                        onRecognitionCompleteListener?.invoke()
                    }
                }

                override fun onResults(results: android.os.Bundle?) {
                    listenLock.release()
                    DebugBus.clearPartialSpeech()
                    audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, originalVolume, 0)
                    val elapsedTime = System.currentTimeMillis() - startTime
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val finalText = matches?.firstOrNull()?.takeIf { it.isNotBlank() }
                        ?: lastPartialText.takeIf { it.isNotBlank() }
                    if (finalText != null) {
                        if (matches.isNullOrEmpty() || matches.first().isBlank()) {
                            // Final results came back empty but partials had text — use the salvage path.
                            Log.i("LISTEN", "Final results empty, using last partial: \"$finalText\"")
                        } else {
                            Log.i("LISTEN", "Recognized text: $finalText after ${elapsedTime}ms")
                        }
                        ConversationLog.append(ConversationLog.Kind.USER_SAID, finalText)
                        if (DebugHandle.echoBackEnabled) {
                            EventBus.publishAsync(Say("You said: $finalText"))
                        }
                        deliverResult(finalText, onComplete)
                        retryCount = 0
                    } else {
                        Log.w("LISTEN", "No speech recognized after ${elapsedTime}ms")
                        // The pre-existing path forgot to fire the completion
                        // callback in the empty-results branch — without
                        // this the agent's listen suspend never resumes.
                        onComplete.invoke(null)
                        onRecognitionCompleteListener?.invoke()
                    }
                }

                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val partial = matches[0].orEmpty()
                        if (partial.isNotBlank()) {
                            // Keep the longest non-blank partial we've seen
                            // — some recognisers stream cumulative text,
                            // others reset between phrases. Either way we
                            // want the richest snapshot for the salvage
                            // path in onResults / onError.
                            if (partial.length >= lastPartialText.length) {
                                lastPartialText = partial
                            }
                        }
                        Log.d("DETAILED_LISTEN", "Partial result: $partial")
                        DebugBus.setPartialSpeech(partial)
                    }
                }
                
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {
                    Log.d("LISTEN", "Event: $eventType")
                }
            })
            
            // In quietRestart mode the patient policy is re-opening the
            // mic in a loop — extend the end-of-speech silence-length
            // extras by [QUIET_RESTART_TIMEOUT_FACTOR] so the
            // recogniser doesn't slam shut after a normal mid-sentence
            // pause and miss the user actually starting to talk.
            val factor = if (quietRestart) QUIET_RESTART_TIMEOUT_FACTOR else 1.0
            val completeMs = (BASE_COMPLETE_SILENCE_MS * factor).toLong()
            val possibleMs = (BASE_POSSIBLE_SILENCE_MS * factor).toLong()
            val minMs = (BASE_MIN_LENGTH_MS * factor).toLong()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.UK)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening...")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Patient pauses: the recogniser's default end-of-speech
                // VAD is ~1-2 s and rushes the user. These give them
                // headroom to think mid-sentence. Not every OEM honours
                // these extras (Samsung's Bixby-backed recogniser
                // sometimes ignores them) — the wall-clock policy in
                // ConversationListenPolicy is the real safety net.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, completeMs)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, possibleMs)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, minMs)
            }
            Log.d(
                "LISTEN",
                "Recogniser timeouts complete=${completeMs}ms possible=${possibleMs}ms " +
                    "min=${minMs}ms quietRestart=$quietRestart",
            )

            startTime = System.currentTimeMillis()
            lastVoiceAtMs = 0L
            lastPartialText = ""
            speechRecognizer?.startListening(intent)
            Log.d("LISTEN", "Started listening for speech at ${java.text.SimpleDateFormat("HH:mm:ss.SSS").format(java.util.Date(startTime))}")
            
            // Set up timeout
            timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
            timeoutHandler?.postDelayed({
                if (listenLock.isListening()) {
                    Log.w("LISTEN", "Speech recognition timeout, stopping...")
                    DebugBus.clearPartialSpeech()
                    stopListening()

                    // Speak back timeout message
//                    EventBus.blockPublish(Say("Speech recognition timed out"))

                    onComplete.invoke(null)
                    // Notify that recognition is complete (timeout)
                    onRecognitionCompleteListener?.invoke()
                }
            }, timeoutDurationMs)

        } catch (e: Exception) {
            Log.e("LISTEN", "Error starting speech recognition: ${e.message}")
            listenLock.release()
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        listenLock.release()
        timeoutHandler?.removeCallbacksAndMessages(null)
        timeoutHandler = null
        Log.d("LISTEN", "Stopped listening")
    }

    /**
     * Common end-of-session path for both [onResults] and the salvage
     * branch of [onError]: route `debug …` straight to [DebugHandle],
     * everything else to the caller's [onComplete], and always fire
     * the recognition-complete listener so wake-word detection can
     * resume.
     *
     * On the debug-command branch [onComplete] is *also* invoked with
     * `null` so the agent's suspended `listenSuspend` resolves —
     * otherwise `Agent.haveConversation` stays parked on a deferred
     * that never completes, `chatting` stays true, and the wake-word
     * loop is permanently suppressed. The exact routing decision lives
     * in [SpeechResultRouter] so it can be unit-tested.
     */
    private fun deliverResult(text: String, onComplete: ((text: String?) -> Unit)) {
        SpeechResultRouter.route(
            text = text,
            onComplete = { resolved ->
                if (resolved != null) {
                    Log.i("LISTEN", "message was $resolved")
                }
                onComplete.invoke(resolved)
            },
            onDebug = { DebugHandle.debugCommand(it) },
        )
        onRecognitionCompleteListener?.invoke()
    }

    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        else -> "UNKNOWN($error)"
    }

    fun setOnRecognitionCompleteListener(listener: () -> Unit) {
        onRecognitionCompleteListener = listener
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        listenLock.release()
        timeoutHandler?.removeCallbacksAndMessages(null)
        timeoutHandler = null
        onRecognitionCompleteListener = null
        Log.d("LISTEN", "Speech recognizer destroyed")
    }

    companion object {
        /**
         * Base end-of-speech timeouts (ms). The user-facing patience
         * is mostly driven by these — bumping them lets users pause
         * longer mid-sentence before STT closes the mic. Matching the
         * pre-existing values so non-loop listens are unchanged.
         */
        private const val BASE_COMPLETE_SILENCE_MS = 4000L
        private const val BASE_POSSIBLE_SILENCE_MS = 3000L
        private const val BASE_MIN_LENGTH_MS = 1500L

        /**
         * Multiplier applied to the end-of-speech timeouts when the
         * caller asked for a `quietRestart`. 1.5× the normal patience
         * stops the recogniser closing the mic before the user has
         * started speaking when we're in the patient-listen loop and
         * the user is still gathering their thoughts.
         */
        private const val QUIET_RESTART_TIMEOUT_FACTOR = 1.5
    }
}
