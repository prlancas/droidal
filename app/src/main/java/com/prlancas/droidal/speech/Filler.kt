package com.prlancas.droidal.speech

import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Short conversational fillers spoken to mask the dead air around slow
 * operations (engine load, time-to-first-token, web search). All fillers
 * publish a [Say] event onto the [EventBus]; [Speak] queues them with
 * `TextToSpeech.QUEUE_ADD` so a filler emitted just before slow work plays
 * first and the real reply queues behind it.
 *
 * Pools are sampled at random with a per-pool "no-repeat-twice-in-a-row"
 * guard so Droidal doesn't sound like it's reading from a script.
 */
object Filler {

    private val LOADING_BRAIN = listOf(
        "Hmm, hang on while I load my brain.",
        "One sec, getting my brain online.",
        "Just a moment while I wake up.",
        "Booting my thoughts, give me a second.",
    )

    private val LOOKING_UP = listOf(
        "I'll just look that up.",
        "Let me check that for you.",
        "One sec, just searching.",
        "Hold on, looking that up now.",
    )

    private val THINKING = listOf(
        "Hmm…",
        "Erm…",
        "Good question.",
        "Let me think about that.",
        "Just a moment.",
        "Hmm, give me a sec.",
    )

    /**
     * Soft, varied "are you still there?" nudges spoken after a long
     * silence. Replaces the old hard-edged "Pardon?" so a thoughtful
     * user pause doesn't feel rushed.
     */
    private val STILL_THERE = listOf(
        "Still there?",
        "Take your time…",
        "I'll wait…",
        "Mmhmm?",
        "No rush.",
    )

    /**
     * Quick wake-word acknowledgements. Spoken the moment the wake
     * word is heard, before STT starts — cues the user to start
     * speaking. Kept short and varied so successive triggers don't
     * sound like a stuck recording.
     */
    private val ACKNOWLEDGED = listOf(
        "Yes?",
        "Mm?",
        "Hmm?",
        "Yes, I'm listening.",
        "Yeah?",
    )

    private val lastPicked = mutableMapOf<List<String>, String>()

    /** Speak a "loading brain" filler. Use before a known slow engine init. */
    fun sayLoadingBrain() {
        EventBus.publishAsync(Say(pick(LOADING_BRAIN)))
    }

    /** Speak a "looking it up" filler. Use at the start of slow lookup tools. */
    fun sayLookingUp() {
        EventBus.publishAsync(Say(pick(LOOKING_UP)))
    }

    /**
     * Speak a soft "still there?" nudge after a long quiet stretch.
     * Used in place of the old hard-edged "Pardon?" so a thoughtful
     * user pause doesn't feel rushed.
     */
    fun sayStillThere() {
        EventBus.publishAsync(Say(pick(STILL_THERE)))
    }

    /**
     * Speak a short wake-word acknowledgement ("Yes?", "Mm?"). Fires
     * an [onComplete] callback when TTS finishes, so the agent can
     * delay starting STT until after the acknowledgement has been
     * spoken (otherwise STT picks up the tail of "yes?" as input).
     */
    fun sayAcknowledged(onComplete: (() -> Unit)? = null) {
        EventBus.publishAsync(Say(pick(ACKNOWLEDGED), onComplete))
    }

    /**
     * Schedule a thinking filler ("Erm…", "Hmm…", "Good question.") to
     * speak after [delayMs] ms unless the returned [Job] is cancelled
     * first. Cancel as soon as the first stream delta arrives so the
     * filler only ever speaks when there's a real silence to mask.
     */
    fun scheduleThinking(scope: CoroutineScope, delayMs: Long = 1500L): Job =
        scope.launch {
            delay(delayMs)
            if (isActive) EventBus.publishAsync(Say(pick(THINKING)))
        }

    @Synchronized
    private fun pick(pool: List<String>): String {
        if (pool.size == 1) return pool[0]
        val previous = lastPicked[pool]
        var choice = pool.random()
        // One re-roll is enough to avoid back-to-back repeats without
        // making the distribution noticeably weird.
        if (choice == previous) choice = pool.random()
        lastPicked[pool] = choice
        return choice
    }
}
