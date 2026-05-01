package com.prlancas.droidal.speech

import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import com.prlancas.droidal.settings.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Buffers streaming LLM deltas into TTS-friendly chunks (sentences, or
 * sentences + clauses depending on [SettingsRepository.streamingMode])
 * and dispatches each chunk as a [Say] event so the user starts hearing
 * the response while the model is still generating later text.
 *
 * Lifecycle:
 *
 *   val streamer = TtsStreamer(mode)
 *   session.send(message, onPartial = streamer::feed)  // streams TTS
 *   streamer.finishAndAwait()                           // suspends until TTS drains
 *
 * Marker handling: `[END_CONVERSATION]` is stripped from the stream and
 * never spoken aloud. Both whole-token and split-across-deltas cases
 * are handled — once the marker appears anywhere in the stream the
 * streamer goes [stopped] and ignores subsequent feed() calls.
 *
 * Thread-safety: [feed] may be called from any thread (LiteRT-LM's
 * native callback in particular). Internal buffer access is guarded by
 * [lock]; [pending] is atomic; [drained] is a thread-safe deferred.
 *
 * Ordering: each completed chunk is dispatched via a private
 * single-threaded [publishScope] so back-to-back Say events arrive at
 * Speak in the order they were produced — `EventBus.publishAsync`
 * normally fans out across `Dispatchers.Default` workers, which can
 * race for two events emitted in quick succession.
 */
class TtsStreamer(
    private val mode: SettingsRepository.StreamingMode = SettingsRepository.StreamingMode.SENTENCE,
    private val stopToken: String = "[END_CONVERSATION]",
    /**
     * How a finished chunk gets dispatched. Production wires this to a
     * single-threaded scope around [EventBus.publish] so back-to-back
     * Say events arrive at [Speak] in order. Tests pass a synchronous
     * lambda to capture utterances and immediately invoke their
     * `onComplete` so [finishAndAwait] resolves.
     */
    private val publishSay: (Say) -> Unit = DefaultSayPublisher,
) {

    private val lock = Any()
    private val buffer = StringBuilder()

    /** Trailing characters of the last delta that could still be the prefix of [stopToken]. */
    private var pendingPrefix: String = ""

    /** True once we've seen [stopToken] in the stream — further [feed] calls are no-ops. */
    @Volatile private var stopped: Boolean = false

    /** Number of Say events dispatched but not yet reported complete by TTS. */
    private val pending = AtomicInteger(0)

    /** Set true by [finishAndAwait]; lets the TTS callback complete [drained] when pending hits 0. */
    @Volatile private var finished: Boolean = false

    private val drained = CompletableDeferred<Unit>()

    /**
     * Feed one streamed delta from the LLM. Safe to call from any
     * thread. After [finishAndAwait] has returned (or [stopped] is
     * true) further deltas are silently dropped.
     */
    fun feed(delta: String) {
        if (delta.isEmpty()) return
        synchronized(lock) {
            if (stopped || finished) return

            // Combine with any chars we held back last time in case
            // they were the start of `stopToken`.
            val combined = pendingPrefix + delta
            pendingPrefix = ""

            val markerIdx = combined.indexOf(stopToken)
            val safeText: String
            if (markerIdx >= 0) {
                // Marker reached: speak everything before it and stop.
                safeText = combined.substring(0, markerIdx)
                stopped = true
            } else {
                // Hold back any suffix that could be the start of the
                // marker — the rest of it might be in the next delta.
                val partial = longestStopPrefixSuffix(combined)
                safeText = combined.substring(0, combined.length - partial)
                pendingPrefix = combined.substring(combined.length - partial)
            }

            if (safeText.isNotEmpty()) {
                buffer.append(safeText)
                flushReadyChunksLocked()
            }
        }
    }

    /**
     * Mark the stream as complete: speak whatever is left in the
     * buffer and suspend until every queued Say has finished playing.
     * Idempotent.
     */
    suspend fun finishAndAwait() {
        synchronized(lock) {
            if (!finished) {
                finished = true
                // Anything left in pendingPrefix that wasn't the marker
                // becomes part of the tail (e.g. trailing "[Note") —
                // append it since at this point we know no more deltas
                // are coming.
                if (pendingPrefix.isNotEmpty() && !stopped) {
                    buffer.append(pendingPrefix)
                    pendingPrefix = ""
                }
                val tail = buffer.toString().trim()
                buffer.setLength(0)
                if (tail.isNotEmpty()) speakLocked(tail)
                if (pending.get() == 0) drained.complete(Unit)
            }
        }
        drained.await()
    }

    /**
     * Inspect the buffer (under [lock]) for ready-to-speak chunks and
     * dispatch them. Leaves any incomplete tail behind for the next
     * feed call or for [finishAndAwait].
     */
    private fun flushReadyChunksLocked() {
        while (true) {
            val text = buffer.toString()
            val end = nextChunkBoundary(text)
            if (end <= 0) return
            val chunk = text.substring(0, end).trim()
            buffer.delete(0, end)
            if (chunk.isNotEmpty()) speakLocked(chunk)
        }
    }

    /**
     * Find the position one past the next sentence-ender (and, in
     * clause mode, also commas/semicolons/colons once the candidate
     * chunk is at least [SettingsRepository.CLAUSE_MIN_CHARS] long).
     * Returns 0 when nothing is ready.
     */
    private fun nextChunkBoundary(s: String): Int {
        for (i in s.indices) {
            val c = s[i]
            val next = if (i + 1 < s.length) s[i + 1] else ' '
            val isSentenceEnd = (c == '.' || c == '!' || c == '?') && next.isWhitespace()
            if (isSentenceEnd) return i + 1

            if (mode == SettingsRepository.StreamingMode.CLAUSE) {
                val isClauseEnd = (c == ',' || c == ';' || c == ':') && next.isWhitespace()
                if (isClauseEnd && (i + 1) >= SettingsRepository.CLAUSE_MIN_CHARS) {
                    return i + 1
                }
            }
        }
        return 0
    }

    /** Length of the longest suffix of [s] that's also a prefix of [stopToken]. */
    private fun longestStopPrefixSuffix(s: String): Int {
        val limit = minOf(s.length, stopToken.length - 1)
        for (k in limit downTo 1) {
            if (stopToken.startsWith(s.substring(s.length - k))) return k
        }
        return 0
    }

    /**
     * Must be called with [lock] held. Increments [pending] before
     * publishing. The chunk is run through [MarkdownStripper] so the
     * synthesiser doesn't read `**bold**` as "asterisk asterisk bold
     * asterisk asterisk" — common output even when the LLM has been
     * told to reply in plain prose. If stripping leaves the chunk empty
     * we drop it instead of queuing a no-op utterance.
     */
    private fun speakLocked(text: String) {
        val spoken = MarkdownStripper.forSpeech(text)
        if (spoken.isBlank()) return
        pending.incrementAndGet()
        publishSay(
            Say(spoken) {
                val left = pending.decrementAndGet()
                if (left == 0 && finished) drained.complete(Unit)
            },
        )
    }
}

/**
 * Production [Say] dispatcher: a private single-threaded coroutine scope
 * over [EventBus.publish], so back-to-back Say events arrive at the TTS
 * subscriber in the order they were produced. `EventBus.publishAsync`
 * normally fans out across `Dispatchers.Default` workers, which can race
 * for two events emitted in quick succession.
 *
 * Kept as an `object` so the scope is shared across the process — every
 * [TtsStreamer] funnels through the same single-threaded dispatcher.
 */
private object DefaultSayPublisher : (Say) -> Unit {

    private val publishScope = CoroutineScope(
        Dispatchers.IO.limitedParallelism(1) + SupervisorJob(),
    )

    override fun invoke(say: Say) {
        publishScope.launch { EventBus.publish(say) }
    }
}
