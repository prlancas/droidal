package com.prlancas.droidal.brain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for the patient listen policy.
 *
 * The new contract is wall-clock-driven:
 *  - Every blank from STT triggers a silent restart (no "Pardon?").
 *  - We give up only after [quietBudgetMs] of total silence.
 *  - If voice was heard recently when the budget elapses, the soft
 *    prompt fires once and the budget is extended.
 *  - If the second budget elapses (or no voice was heard), we return
 *    null so the agent ends the conversation.
 *
 * All Android dependencies are dependency-injected, so these run as
 * plain JVM unit tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationListenPolicyTest {

    /**
     * Scripted listener that yields replies in order and lets the
     * test drive a fake clock between calls. Each blank "consumes"
     * a configurable chunk of fake time, mirroring how the real
     * recogniser would block for ~its silence-length budget.
     */
    private class ScriptedListener(
        private val replies: List<String?>,
        private val msPerBlank: Long = 500L,
        private val msPerNonBlank: Long = 100L,
        private val clock: MutableClock,
    ) {
        val calls = mutableListOf<Boolean>()
        private var idx = 0
        val listen: suspend (Boolean) -> String? = { silent ->
            calls += silent
            val reply = if (idx < replies.size) replies[idx++] else null
            clock.advance(if (reply.isNullOrBlank()) msPerBlank else msPerNonBlank)
            reply
        }
    }

    private class MutableClock(var now: Long = 0L) {
        val nowMs: () -> Long = { now }
        fun advance(ms: Long) { now += ms }
    }

    private class CapturingPrompt {
        var firedAt: Long? = null
        fun bind(clock: MutableClock): () -> Unit = {
            firedAt = clock.now
        }
    }

    @Test
    fun `returns first non-blank reply immediately`() = runTest {
        val clock = MutableClock()
        val listener = ScriptedListener(listOf("hello droidal"), clock = clock)
        val prompt = CapturingPrompt()

        val result = ConversationListenPolicy.listenPatiently(
            quietBudgetMs = 5_000L,
            listen = listener.listen,
            voiceHeardWithinMs = { 0L },
            softPrompt = prompt.bind(clock),
            nowMs = clock.nowMs,
        )

        assertEquals("hello droidal", result)
        // Always silent — the recogniser's built-in error speech is
        // suppressed across the board now.
        assertEquals(listOf(true), listener.calls)
        assertNull("Soft prompt must not fire before the budget elapses", prompt.firedAt)
    }

    @Test
    fun `silently restarts on blanks while inside the budget`() = runTest {
        // Budget = 5s, each blank consumes 500ms — first non-blank
        // arrives at ~3500ms (well within budget). Should NOT trigger
        // the soft prompt.
        val clock = MutableClock()
        val listener = ScriptedListener(
            replies = listOf(null, null, null, null, null, null, "hi"),
            msPerBlank = 500L,
            clock = clock,
        )
        val prompt = CapturingPrompt()

        val result = ConversationListenPolicy.listenPatiently(
            quietBudgetMs = 5_000L,
            listen = listener.listen,
            voiceHeardWithinMs = { 0L },
            softPrompt = prompt.bind(clock),
            nowMs = clock.nowMs,
        )

        assertEquals("hi", result)
        assertEquals(7, listener.calls.size)
        assertTrue(
            "Every listen call must be silent",
            listener.calls.all { it },
        )
        assertNull("Soft prompt must not fire while inside the budget", prompt.firedAt)
    }

    @Test
    fun `soft-prompts and extends once when voice was heard recently`() = runTest {
        // Budget = 1000ms; each blank consumes 600ms so the budget
        // elapses on the second blank. Voice was heard 100ms ago, so
        // the soft prompt fires and the budget extends. Then the user
        // finally replies on the third call.
        val clock = MutableClock()
        val listener = ScriptedListener(
            replies = listOf(null, null, "yes I'm here"),
            msPerBlank = 600L,
            clock = clock,
        )
        val prompt = CapturingPrompt()

        val result = ConversationListenPolicy.listenPatiently(
            quietBudgetMs = 1_000L,
            listen = listener.listen,
            voiceHeardWithinMs = { 100L }, // recent voice
            softPrompt = prompt.bind(clock),
            nowMs = clock.nowMs,
        )

        assertEquals("yes I'm here", result)
        assertEquals(3, listener.calls.size)
        assertTrue("Soft prompt should have fired exactly once", prompt.firedAt != null)
    }

    @Test
    fun `gives up after budget when no recent voice`() = runTest {
        // Budget = 1000ms; blanks consume 600ms each so budget is
        // gone by the second call. No voice activity at all → return
        // null without speaking.
        val clock = MutableClock()
        val listener = ScriptedListener(
            replies = listOf(null, null, null, null, null),
            msPerBlank = 600L,
            clock = clock,
        )
        val prompt = CapturingPrompt()

        val result = ConversationListenPolicy.listenPatiently(
            quietBudgetMs = 1_000L,
            listen = listener.listen,
            voiceHeardWithinMs = { Long.MAX_VALUE }, // no voice ever
            softPrompt = prompt.bind(clock),
            nowMs = clock.nowMs,
        )

        assertNull("Without recent voice, policy must give up after budget", result)
        assertEquals(
            "Should stop calling listen as soon as the budget elapses",
            2,
            listener.calls.size,
        )
        assertNull("Soft prompt must not fire when no voice was heard", prompt.firedAt)
    }

    @Test
    fun `gives up after the extension also elapses`() = runTest {
        // Budget = 1000ms; blanks consume 600ms each. First budget
        // elapses on the second blank, voice was recent so we extend.
        // Second budget elapses on the fourth blank with still no
        // reply — final answer is null.
        val clock = MutableClock()
        val listener = ScriptedListener(
            replies = List(20) { null },
            msPerBlank = 600L,
            clock = clock,
        )
        val prompt = CapturingPrompt()

        val result = ConversationListenPolicy.listenPatiently(
            quietBudgetMs = 1_000L,
            listen = listener.listen,
            voiceHeardWithinMs = { 100L },
            softPrompt = prompt.bind(clock),
            nowMs = clock.nowMs,
        )

        assertNull("Two elapsed budgets should give up", result)
        assertTrue("Soft prompt must have fired (once)", prompt.firedAt != null)
        // First budget covers blanks 1-2 (~1200ms), prompt fires
        // before blank 3, second budget covers 3-4 (~1200ms more),
        // give up before blank 5.
        assertEquals(4, listener.calls.size)
    }

    @Test
    fun `treats blank-string reply as silence`() = runTest {
        val clock = MutableClock()
        val listener = ScriptedListener(
            replies = listOf("   ", "\t\n", "hi"),
            clock = clock,
        )
        val prompt = CapturingPrompt()

        val result = ConversationListenPolicy.listenPatiently(
            quietBudgetMs = 5_000L,
            listen = listener.listen,
            voiceHeardWithinMs = { 0L },
            softPrompt = prompt.bind(clock),
            nowMs = clock.nowMs,
        )

        assertEquals("hi", result)
        assertEquals(3, listener.calls.size)
        assertNull(prompt.firedAt)
    }

    @Test
    fun `non-positive quietBudgetMs is rejected`() = runTest {
        try {
            ConversationListenPolicy.listenPatiently(
                quietBudgetMs = 0L,
                listen = { _ -> "shouldn't be called" },
                voiceHeardWithinMs = { 0L },
                softPrompt = {},
                nowMs = { 0L },
            )
            fail("Expected IllegalArgumentException for non-positive budget")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                expected.message.orEmpty().contains("quietBudgetMs"),
            )
        }
    }

    @Test
    fun `soft-prompts and extends when face is visible even if voice is stale`() = runTest {
        // Budget = 1000ms; blanks consume 600ms so the budget elapses
        // on the second blank. Voice is stale (no spike), but the
        // camera says the user's face was seen 200ms ago — they're
        // standing right there, just thinking. Treat that as "still
        // here", soft-prompt once, and extend.
        val clock = MutableClock()
        val listener = ScriptedListener(
            replies = listOf(null, null, "ok yes"),
            msPerBlank = 600L,
            clock = clock,
        )
        val prompt = CapturingPrompt()

        val result = ConversationListenPolicy.listenPatiently(
            quietBudgetMs = 1_000L,
            listen = listener.listen,
            voiceHeardWithinMs = { Long.MAX_VALUE }, // no voice ever
            faceVisibleWithinMs = { 200L }, // face right here
            softPrompt = prompt.bind(clock),
            nowMs = clock.nowMs,
        )

        assertEquals("ok yes", result)
        assertEquals(3, listener.calls.size)
        assertTrue(
            "Soft prompt must fire once when face is visible despite stale voice",
            prompt.firedAt != null,
        )
    }

    @Test
    fun `gives up only when both voice and face are stale`() = runTest {
        // Budget = 1000ms; blanks consume 600ms so budget is gone by
        // the second call. Neither voice nor face has been seen
        // recently — user has clearly walked away, end the listen.
        val clock = MutableClock()
        val listener = ScriptedListener(
            replies = listOf(null, null, null, null, null),
            msPerBlank = 600L,
            clock = clock,
        )
        val prompt = CapturingPrompt()

        val result = ConversationListenPolicy.listenPatiently(
            quietBudgetMs = 1_000L,
            listen = listener.listen,
            voiceHeardWithinMs = { Long.MAX_VALUE },
            faceVisibleWithinMs = { Long.MAX_VALUE },
            softPrompt = prompt.bind(clock),
            nowMs = clock.nowMs,
        )

        assertNull("Without any presence signal, policy must give up after budget", result)
        assertEquals(
            "Should stop calling listen as soon as the budget elapses",
            2,
            listener.calls.size,
        )
        assertNull("Soft prompt must not fire when neither signal is recent", prompt.firedAt)
    }

    @Test
    fun `face presence alone keeps the listen alive across the soft prompt`() = runTest {
        // Budget = 1000ms; blanks consume 600ms each. First budget
        // elapses on the second blank; only the face signal is
        // present (user is silent but visible) and the soft prompt
        // fires + extends. User finally replies before the second
        // budget elapses.
        val clock = MutableClock()
        val listener = ScriptedListener(
            replies = listOf(null, null, "found my words"),
            msPerBlank = 600L,
            clock = clock,
        )
        val prompt = CapturingPrompt()

        val result = ConversationListenPolicy.listenPatiently(
            quietBudgetMs = 1_000L,
            listen = listener.listen,
            voiceHeardWithinMs = { Long.MAX_VALUE },
            faceVisibleWithinMs = { 500L },
            softPrompt = prompt.bind(clock),
            nowMs = clock.nowMs,
        )

        assertEquals("found my words", result)
        assertTrue("Soft prompt fires once for the face-only case", prompt.firedAt != null)
    }

    @Test
    fun `bails out after MAX_INSTANT_BLANKS consecutive instant-blank cycles`() = runTest {
        // Simulate a misbehaving recogniser that returns null
        // immediately every call without consuming any wall-clock
        // time. Without the circuit breaker this loop would spin
        // forever firing the listening beep — with it we back off
        // [MIN_LISTEN_MS] each cycle and bail entirely after
        // [MAX_INSTANT_BLANKS] in a row.
        val clock = MutableClock()
        var calls = 0
        val sleepCalls = mutableListOf<Long>()
        val prompt = CapturingPrompt()

        val result = ConversationListenPolicy.listenPatiently(
            // Long budget so the deadline branch never fires — we're
            // testing the instant-blank breaker on its own.
            quietBudgetMs = 60_000L,
            listen = { _ ->
                calls++
                null
            },
            voiceHeardWithinMs = { 0L },
            faceVisibleWithinMs = { 0L },
            softPrompt = prompt.bind(clock),
            nowMs = clock.nowMs,
            sleepMs = { ms -> sleepCalls += ms },
        )

        assertNull("Recogniser bouncing must not produce a transcript", result)
        assertEquals(
            "Loop should bail after exactly MAX_INSTANT_BLANKS instant cycles",
            ConversationListenPolicy.MAX_INSTANT_BLANKS,
            calls,
        )
        assertEquals(
            "Back-off fires for every cycle except the final, bail-out one",
            ConversationListenPolicy.MAX_INSTANT_BLANKS - 1,
            sleepCalls.size,
        )
        assertTrue(
            "Each back-off must request the full MIN_LISTEN_MS pause (clock didn't move at all)",
            sleepCalls.all { it == ConversationListenPolicy.MIN_LISTEN_MS },
        )
        assertNull(
            "Soft prompt must not fire on the recogniser-broken path",
            prompt.firedAt,
        )
    }

    @Test
    fun `slow blanks do not trip the circuit breaker or back-off`() = runTest {
        // Each blank consumes 1500ms, well above MIN_LISTEN_MS, so
        // the recogniser is clearly engaging. The streak resets
        // every iteration and the policy waits patiently until the
        // user speaks — no back-off sleep, no premature bail.
        val clock = MutableClock()
        val listener = ScriptedListener(
            replies = listOf(null, null, null, null, "yes"),
            msPerBlank = 1_500L,
            clock = clock,
        )
        val sleepCalls = mutableListOf<Long>()

        val result = ConversationListenPolicy.listenPatiently(
            quietBudgetMs = 60_000L,
            listen = listener.listen,
            voiceHeardWithinMs = { 100L },
            softPrompt = {},
            nowMs = clock.nowMs,
            sleepMs = { ms -> sleepCalls += ms },
        )

        assertEquals("yes", result)
        assertEquals(5, listener.calls.size)
        assertTrue(
            "Slow (real) blanks must never invoke the back-off path",
            sleepCalls.isEmpty(),
        )
    }

    @Test
    fun `face that disappeared longer than the window does not extend`() = runTest {
        // Budget = 1000ms. Face was last seen 30s ago — that's well
        // outside RECENT_FACE_WINDOW_MS so it must not count as a
        // presence signal. With voice also stale, the listen ends.
        val clock = MutableClock()
        val listener = ScriptedListener(
            replies = listOf(null, null, null, null),
            msPerBlank = 600L,
            clock = clock,
        )
        val prompt = CapturingPrompt()

        val result = ConversationListenPolicy.listenPatiently(
            quietBudgetMs = 1_000L,
            listen = listener.listen,
            voiceHeardWithinMs = { Long.MAX_VALUE },
            faceVisibleWithinMs = { 30_000L },
            softPrompt = prompt.bind(clock),
            nowMs = clock.nowMs,
        )

        assertNull("A long-stale face must not count as presence", result)
        assertNull("Soft prompt must not fire when face is also stale", prompt.firedAt)
    }
}
