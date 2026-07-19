package com.prlancas.droidal.listen

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.prlancas.droidal.MainActivity
import com.prlancas.droidal.brain.Agent
import com.prlancas.droidal.debug.DebugActivityState
import com.prlancas.droidal.debug.DebugBus
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.StartConversation
import com.prlancas.droidal.settings.SettingsRepository
import com.prlancas.droidal.speech.SpeechToText
import com.prlancas.droidal.status.GlobalStatus
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the microphone when Droidal is idle and decides when to hand it
 * over to the [Agent].
 *
 * Picovoice removed their free tier, so the old Porcupine keyword engine
 * is gone. Instead Droidal runs the same Google
 * [android.speech.SpeechRecognizer] it uses for the conversation in a
 * continuous "wake-listen" loop: the recogniser's own voice-activity
 * detection (VAD) segments speech, we transcribe each utterance, and
 * [WakeWordMatcher] decides whether it should wake Droidal (wake-word
 * regex match, a visible face, or the "always respond" override). On a
 * wake we publish [StartConversation] with the recognised text so the
 * agent responds to what was actually said instead of prompting again.
 *
 * Mic hand-off:
 *  - While the wake loop is active, [wakeListening] is `true`.
 *  - When a conversation starts, [Agent] pulls the mic via [listenOnly];
 *    the loop stands down and does not restart until
 *    [resumeWakeWordDetection] is called from `Agent`'s finally block.
 */
object Listen {

    private const val TAG = "LISTEN"

    private lateinit var mainActivity: MainActivity
    private lateinit var speechToText: SpeechToText

    /** True once [SpeechToText] has been built. Guards [init] so it's
     *  safe to call from more than one place / on activity re-create. */
    @Volatile
    private var initialized = false

    /** True while the passive wake-listen loop owns the mic. Acts as the
     *  single-owner guard so overlapping [startWakeWordDetection] calls
     *  can't spin up two recognisers. */
    private val wakeListening = AtomicBoolean(false)

    /** Current wake decision policy, rebuilt from settings on [init] and
     *  whenever the settings page calls [reloadWakeWord]. */
    @Volatile
    private var matcher: WakeWordMatcher = WakeWordMatcher.build(
        SettingsRepository.DEFAULT_WAKE_REGEX,
        alwaysTrigger = false,
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    fun init(mainActivity: MainActivity, context: Context) {
        this.mainActivity = mainActivity

        // Already built on an earlier call / onCreate — just make sure the
        // wake loop is running again (unless a conversation owns the mic).
        if (initialized) {
            resumeWakeWordDetection()
            return
        }

        try {
            speechToText = SpeechToText(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SpeechToText: ${e.message}")
            return
        }
        matcher = WakeWordMatcher.fromSettings(SettingsRepository.get(context))
        initialized = true
        startWakeWordDetection()
    }

    /**
     * Rebuild the wake decision policy from the latest settings *while the
     * app is running* — triggered by the wake-word settings page so a
     * changed regex / the always-respond toggle takes effect without a
     * restart. Safe to call before [init] (no-op until then).
     */
    @Synchronized
    fun reloadWakeWord(context: Context) {
        if (!initialized) {
            Log.d(TAG, "reloadWakeWord called before init — ignoring")
            return
        }
        matcher = WakeWordMatcher.fromSettings(SettingsRepository.get(context))
        Log.i(TAG, "Reloaded wake matcher from settings")
    }

    /**
     * Start (or resume) the continuous wake-listen loop. No-op if a
     * conversation currently owns the mic or the loop is already running.
     */
    private fun startWakeWordDetection() {
        if (!initialized || Agent.isChatting()) return
        if (!wakeListening.compareAndSet(false, true)) return
        Log.d(TAG, "Starting wake-word detection (VAD + STT)")
        DebugBus.setActivity(DebugActivityState.LISTENING_FOR_WAKE_WORD)
        listenForWakeOnce()
    }

    /** One pass of the wake loop: transcribe the next utterance, then
     *  route the result through [onWakeResult]. Must run on the main
     *  thread — [SpeechToText] creates the recogniser there. */
    private fun listenForWakeOnce() {
        mainHandler.post {
            if (!wakeListening.get() || Agent.isChatting()) {
                wakeListening.set(false)
                return@post
            }
            // Passive listening: no beep (quietRestart) and keep the
            // "Listening for wake word" overlay instead of flipping to
            // "Listening to user" (wakeMode).
            speechToText.startListening(quietRestart = true, wakeMode = true) { text ->
                onWakeResult(text)
            }
        }
    }

    private fun onWakeResult(text: String?) {
        if (Agent.isChatting()) {
            wakeListening.set(false)
            return
        }
        val faceVisible = msSinceLastFaceSeen() <= FACE_VISIBLE_WINDOW_MS
        val decision = matcher.evaluate(text.orEmpty(), faceVisible)
        if (decision.wake) {
            // Stand the loop down; the agent takes the mic from here and
            // resumeWakeWordDetection() restarts us when it's done.
            wakeListening.set(false)
            Log.i(TAG, "Woke on: \"${decision.message}\" (faceVisible=$faceVisible)")
            EventBus.publishAsync(
                StartConversation(startedByUser = true, message = decision.message, user = null),
            )
        } else {
            // Nothing to act on — listen again after a short delay so a
            // fast-failing recogniser (ERROR_CLIENT / _BUSY) can't spin us
            // into a tight loop.
            mainHandler.postDelayed({
                if (wakeListening.get() && !Agent.isChatting()) {
                    listenForWakeOnce()
                } else {
                    wakeListening.set(false)
                }
            }, WAKE_RESTART_DELAY_MS)
        }
    }

    /**
     * Listen-only entry-point used by the [Agent] during a conversation.
     * Stands the passive wake loop down (freeing / aborting its recogniser
     * session) and starts a normal STT listen. The wake loop is restarted
     * by [resumeWakeWordDetection] when the conversation ends.
     *
     * @param quietRestart when `true`, mute the "I'm listening" start/stop
     *   beep and extend the recogniser's end-of-speech silence timeouts —
     *   used for every listen after the first in a conversation.
     */
    fun listenOnly(
        quietRestart: Boolean = false,
        onComplete: ((text: String?) -> Unit),
    ) {
        if (!initialized) {
            onComplete(null)
            return
        }
        // Take the loop offline and abort any in-flight wake recognition so
        // the conversation listen owns the mic cleanly.
        wakeListening.set(false)
        mainHandler.post {
            runCatching { speechToText.stopListening() }
            speechToText.startListening(quietRestart = quietRestart, onComplete = onComplete)
        }
    }

    /**
     * Resume the wake-listen loop from outside [Listen] — used by
     * `Agent.haveConversation`'s finally block once a conversation is
     * fully wound down. Safe to call before [init] (no-op).
     */
    fun resumeWakeWordDetection() {
        if (!initialized) {
            Log.d(TAG, "resumeWakeWordDetection called before init — ignoring")
            return
        }
        startWakeWordDetection()
    }

    /**
     * Milliseconds since the recogniser last heard voice (RMS spike over
     * its threshold), or [Long.MAX_VALUE] when no voice has been detected
     * this listen yet. Used by `ConversationListenPolicy.listenPatiently`.
     */
    fun msSinceLastVoice(): Long {
        if (!::speechToText.isInitialized) return Long.MAX_VALUE
        val last = speechToText.lastVoiceAtMs()
        if (last == 0L) return Long.MAX_VALUE
        return (System.currentTimeMillis() - last).coerceAtLeast(0L)
    }

    /**
     * Milliseconds since the camera last saw a face, or [Long.MAX_VALUE]
     * when no face has ever been seen this run. Companion to
     * [msSinceLastVoice] for the patient listen policy, and used here so a
     * user who's looking at Droidal and talking is always heard.
     */
    fun msSinceLastFaceSeen(): Long {
        val last = GlobalStatus.lastFaceSeenAtMs
        if (last == 0L) return Long.MAX_VALUE
        return (System.currentTimeMillis() - last).coerceAtLeast(0L)
    }

    private const val WAKE_RESTART_DELAY_MS = 400L

    /**
     * How recently the camera must have seen a face for a non-wake-word
     * utterance to still start a conversation. Generous enough to cover
     * the recogniser's end-of-speech silence timeout (a few seconds pass
     * between the user finishing talking and the transcript arriving).
     */
    private const val FACE_VISIBLE_WINDOW_MS = 7_000L
}
