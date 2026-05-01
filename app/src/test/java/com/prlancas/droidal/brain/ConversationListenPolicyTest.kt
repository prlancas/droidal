package com.prlancas.droidal.brain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for the conversation listen-with-retry policy.
 *
 * This is the "user journey" piece of the agent that decides what to do
 * when STT comes back blank: how many times to re-listen, when to ask
 * "Pardon?", and when to give up. Mocking out the STT side lets us
 * cover all the edge cases without spinning up a real recogniser.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationListenPolicyTest {

    /**
     * Helper that builds a `listen` callback yielding scripted replies
     * in order. Records every call so we can assert on the silent-flag
     * sequence the policy used.
     */
    private class ScriptedListener(private val replies: List<String?>) {
        val calls = mutableListOf<Boolean>()
        private var idx = 0
        val listen: suspend (Boolean) -> String? = { silent ->
            calls += silent
            if (idx < replies.size) replies[idx++] else null
        }
    }

    private class CapturingSay {
        val said = mutableListOf<String>()
        val say: (String) -> Unit = { said += it }
    }

    @Test
    fun `returns first non-blank reply immediately`() = runTest {
        val listener = ScriptedListener(listOf("hello droidal"))
        val say = CapturingSay()

        val result = ConversationListenPolicy.listenWithRetry(
            maxBlankRetries = 30,
            listen = listener.listen,
            say = say.say,
        )

        assertEquals("hello droidal", result)
        // Exactly one listen call, with silent=false on the first attempt.
        assertEquals(listOf(false), listener.calls)
        // No "Pardon?" — the user replied first time.
        assertTrue(say.said.isEmpty())
    }

    @Test
    fun `recovers after a single blank then a real reply`() = runTest {
        val listener = ScriptedListener(listOf(null, "hello"))
        val say = CapturingSay()

        val result = ConversationListenPolicy.listenWithRetry(
            maxBlankRetries = 30,
            listen = listener.listen,
            say = say.say,
        )

        assertEquals("hello", result)
        // First call silent=false, second call silent=true (subsequent retry).
        assertEquals(listOf(false, true), listener.calls)
        // Exactly one "Pardon?" — emitted right after the first blank.
        assertEquals(listOf("Pardon?"), say.said)
    }

    @Test
    fun `says Pardon at most once across a long blank streak`() = runTest {
        // Five blanks then real input — even though we listened six
        // times we should only have nudged the user verbally once.
        val listener = ScriptedListener(
            listOf(null, null, null, null, null, "ok i'm here"),
        )
        val say = CapturingSay()

        val result = ConversationListenPolicy.listenWithRetry(
            maxBlankRetries = 30,
            listen = listener.listen,
            say = say.say,
        )

        assertEquals("ok i'm here", result)
        assertEquals(
            "Should call listen six times — five blanks + the real reply",
            6,
            listener.calls.size,
        )
        // First call NOT silent, every retry IS silent.
        assertEquals(false, listener.calls.first())
        for (i in 1 until listener.calls.size) {
            assertTrue(
                "Retry #$i should listen silently",
                listener.calls[i],
            )
        }
        assertEquals(listOf("Pardon?"), say.said)
    }

    @Test
    fun `gives up after maxBlankRetries blanks`() = runTest {
        val maxBlankRetries = 3
        // Always blank — must give up after maxBlankRetries+1 attempts.
        val listener = ScriptedListener(emptyList())
        val say = CapturingSay()

        val result = ConversationListenPolicy.listenWithRetry(
            maxBlankRetries = maxBlankRetries,
            listen = listener.listen,
            say = say.say,
        )

        assertNull("Policy should give up after the cap", result)
        assertEquals(
            "Total attempts = maxBlankRetries + 1",
            maxBlankRetries + 1,
            listener.calls.size,
        )
        // Still only one "Pardon?" the entire streak.
        assertEquals(listOf("Pardon?"), say.said)
    }

    @Test
    fun `treats blank-string reply as silence`() = runTest {
        val listener = ScriptedListener(listOf("   ", "\t\n", "hi"))
        val say = CapturingSay()
        val result = ConversationListenPolicy.listenWithRetry(
            maxBlankRetries = 30,
            listen = listener.listen,
            say = say.say,
        )
        assertEquals("hi", result)
        // Two silent retries before the real reply.
        assertEquals(listOf(false, true, true), listener.calls)
    }

    @Test
    fun `with maxBlankRetries=0 a single blank ends the streak`() = runTest {
        // Edge case — caller wants no retries. A single attempt then
        // bail. We still nudge "Pardon?" once for consistency, but the
        // policy should NOT call listen again.
        val listener = ScriptedListener(listOf(null))
        val say = CapturingSay()
        val result = ConversationListenPolicy.listenWithRetry(
            maxBlankRetries = 0,
            listen = listener.listen,
            say = say.say,
        )
        assertNull(result)
        assertEquals(1, listener.calls.size)
    }

    @Test
    fun `negative maxBlankRetries is rejected`() = runTest {
        try {
            ConversationListenPolicy.listenWithRetry(
                maxBlankRetries = -1,
                listen = { _ -> "shouldn't be called" },
                say = {},
            )
            fail("Expected IllegalArgumentException for negative retry count")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                expected.message.orEmpty().contains("maxBlankRetries"),
            )
        }
    }
}
