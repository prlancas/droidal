package com.prlancas.droidal.speech

import com.prlancas.droidal.event.events.Say
import com.prlancas.droidal.settings.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TtsStreamer].
 *
 * The streamer is the bridge between an LLM that emits tokens
 * incrementally and a TTS engine that wants whole sentences. Getting
 * any of these wrong shows up immediately as poor UX:
 *
 *  - Sentence-boundary detection: too eager and TTS reads commas as
 *    sentence ends; too lazy and TTS waits for the entire reply before
 *    speaking.
 *  - `[END_CONVERSATION]` marker handling: the marker is a control
 *    signal that must NEVER reach TTS — even when split across two
 *    deltas — and it must truncate the spoken reply at the marker
 *    rather than speaking the half-marker before bailing.
 *  - Ordering: chunks must arrive at TTS in the order they were
 *    produced, regardless of producer thread.
 *  - `finishAndAwait` must drain — if it returns early, the agent
 *    moves on to listening while Droidal is still mid-sentence.
 *
 * To keep tests free of a real EventBus / Speak setup we inject a
 * synchronous publisher that captures Say events and immediately
 * triggers their `onComplete` (mimicking the real TTS engine reporting
 * an utterance done).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsStreamerTest {

    private class CapturingPublisher : (Say) -> Unit {
        val captured = mutableListOf<String>()
        override fun invoke(say: Say) {
            captured += say.sentence
            // Simulate the real Speak engine reporting completion so
            // [TtsStreamer.finishAndAwait]'s pending counter drains.
            say.onComplete?.invoke()
        }
    }

    private fun newStreamer(
        mode: SettingsRepository.StreamingMode = SettingsRepository.StreamingMode.SENTENCE,
        publisher: CapturingPublisher = CapturingPublisher(),
    ): Pair<TtsStreamer, CapturingPublisher> {
        return TtsStreamer(mode = mode, publishSay = publisher) to publisher
    }

    @Test
    fun `sentence mode flushes one whole sentence at a time`() = runTest {
        val (streamer, pub) = newStreamer()
        streamer.feed("Hello world. ")
        streamer.feed("How are you? ")
        streamer.finishAndAwait()
        assertEquals(listOf("Hello world.", "How are you?"), pub.captured)
    }

    @Test
    fun `sentence mode does not flush mid-sentence on commas`() = runTest {
        val (streamer, pub) = newStreamer()
        streamer.feed("First clause, second clause, ")
        // No flush yet — no sentence-end has appeared.
        assertTrue(pub.captured.isEmpty())
        streamer.feed("third clause. ")
        // Now the whole thing should have flushed as one chunk.
        streamer.finishAndAwait()
        assertEquals(
            listOf("First clause, second clause, third clause."),
            pub.captured,
        )
    }

    @Test
    fun `clause mode flushes long-enough fragments on commas`() = runTest {
        val (streamer, pub) = newStreamer(SettingsRepository.StreamingMode.CLAUSE)
        // Long enough to clear CLAUSE_MIN_CHARS (25) — should flush on comma.
        streamer.feed("This is a sufficiently long opening clause, ")
        assertEquals(
            listOf("This is a sufficiently long opening clause,"),
            pub.captured,
        )
        streamer.feed("then more. ")
        streamer.finishAndAwait()
        assertEquals(
            listOf(
                "This is a sufficiently long opening clause,",
                "then more.",
            ),
            pub.captured,
        )
    }

    @Test
    fun `clause mode does not flush tiny clauses on commas`() = runTest {
        val (streamer, pub) = newStreamer(SettingsRepository.StreamingMode.CLAUSE)
        // "Yes, " is shorter than CLAUSE_MIN_CHARS, so no flush yet.
        streamer.feed("Yes, ")
        assertTrue(pub.captured.isEmpty())
        streamer.feed("happy to help. ")
        streamer.finishAndAwait()
        assertEquals(listOf("Yes, happy to help."), pub.captured)
    }

    @Test
    fun `whole stop token in one delta truncates at the marker`() = runTest {
        val (streamer, pub) = newStreamer()
        streamer.feed("Goodbye for now. [END_CONVERSATION] anything after")
        streamer.finishAndAwait()
        // The control marker (and anything after it) must never reach TTS.
        assertEquals(listOf("Goodbye for now."), pub.captured)
        for (chunk in pub.captured) {
            assertFalse(
                "Marker text leaked into a spoken chunk: '$chunk'",
                chunk.contains("END_CONVERSATION"),
            )
            assertFalse(chunk.contains("anything after"))
        }
    }

    @Test
    fun `stop token split across two deltas is still suppressed`() = runTest {
        val (streamer, pub) = newStreamer()
        streamer.feed("All done. [END_CONV")
        // The trailing partial marker must be held back, NOT spoken.
        for (chunk in pub.captured) {
            assertFalse(chunk.contains("[END_CONV"))
        }
        streamer.feed("ERSATION] tail")
        streamer.finishAndAwait()
        assertEquals(listOf("All done."), pub.captured)
    }

    @Test
    fun `feed after stop token is silently dropped`() = runTest {
        val (streamer, pub) = newStreamer()
        streamer.feed("Bye. [END_CONVERSATION]")
        streamer.feed("This must not be spoken. ")
        streamer.finishAndAwait()
        assertEquals(listOf("Bye."), pub.captured)
    }

    @Test
    fun `finishAndAwait flushes a tail with no trailing punctuation`() = runTest {
        val (streamer, pub) = newStreamer()
        streamer.feed("No trailing dot")
        streamer.finishAndAwait()
        assertEquals(listOf("No trailing dot"), pub.captured)
    }

    @Test
    fun `finishAndAwait is idempotent`() = runTest {
        val (streamer, pub) = newStreamer()
        streamer.feed("Hello.")
        streamer.finishAndAwait()
        streamer.finishAndAwait() // second call must not throw / re-emit
        assertEquals(listOf("Hello."), pub.captured)
    }

    @Test
    fun `markdown leaks are stripped before reaching TTS`() = runTest {
        val (streamer, pub) = newStreamer()
        streamer.feed("**Important.** ")
        streamer.finishAndAwait()
        // The streamer should have routed through MarkdownStripper —
        // no asterisks should reach the spoken chunk.
        assertEquals(listOf("Important."), pub.captured)
    }

    @Test
    fun `chunks are dispatched in arrival order`() = runTest {
        val (streamer, pub) = newStreamer()
        // Three sentences arriving in quick succession across multiple
        // feed calls. With the synchronous publisher we get strict
        // ordering for free; production wraps the same publishSay in a
        // single-threaded scope to preserve the same guarantee.
        streamer.feed("One. ")
        streamer.feed("Two. ")
        streamer.feed("Three. ")
        streamer.finishAndAwait()
        assertEquals(listOf("One.", "Two.", "Three."), pub.captured)
    }

    @Test
    fun `empty deltas are no-ops`() = runTest {
        val (streamer, pub) = newStreamer()
        streamer.feed("")
        streamer.feed("Hi. ")
        streamer.feed("")
        streamer.finishAndAwait()
        assertEquals(listOf("Hi."), pub.captured)
    }
}
