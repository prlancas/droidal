package com.prlancas.droidal.brain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [ListenAggregator] — the wrapper that turns a sequence
 * of consecutive STT sessions into one logical user turn.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListenAggregatorTest {

    private class ScriptedTail(private val replies: List<String?>) {
        var calls = 0
        val tailSegment: suspend (Long) -> String? = { _ ->
            val r = if (calls < replies.size) replies[calls] else null
            calls++
            r
        }
    }

    @Test
    fun `null first segment returns null`() = runTest {
        val tail = ScriptedTail(emptyList())
        val result = ListenAggregator.listenOneTurn(
            firstSegment = { null },
            tailSegment = tail.tailSegment,
        )
        assertNull(result)
        assertEquals("Tail must not be consulted when first segment is empty", 0, tail.calls)
    }

    @Test
    fun `blank first segment returns null`() = runTest {
        val tail = ScriptedTail(emptyList())
        val result = ListenAggregator.listenOneTurn(
            firstSegment = { "   \t\n" },
            tailSegment = tail.tailSegment,
        )
        assertNull(result)
        assertEquals(0, tail.calls)
    }

    @Test
    fun `single segment with blank tail returns just the first`() = runTest {
        val tail = ScriptedTail(listOf(null))
        val result = ListenAggregator.listenOneTurn(
            firstSegment = { "hello" },
            tailSegment = tail.tailSegment,
        )
        assertEquals("hello", result)
        // One tail call to confirm no continuation came.
        assertEquals(1, tail.calls)
    }

    @Test
    fun `concatenates two segments split by a pause`() = runTest {
        val tail = ScriptedTail(listOf("the weather", null))
        val result = ListenAggregator.listenOneTurn(
            firstSegment = { "tell me about" },
            tailSegment = tail.tailSegment,
        )
        assertEquals("tell me about the weather", result)
        assertEquals(2, tail.calls)
    }

    @Test
    fun `concatenates a long chain of segments`() = runTest {
        val tail = ScriptedTail(listOf("about", "the", "weather", "today", null))
        val result = ListenAggregator.listenOneTurn(
            firstSegment = { "tell me" },
            tailSegment = tail.tailSegment,
        )
        assertEquals("tell me about the weather today", result)
        assertEquals(5, tail.calls)
    }

    @Test
    fun `trims whitespace off each segment before joining`() = runTest {
        val tail = ScriptedTail(listOf("  the weather  ", null))
        val result = ListenAggregator.listenOneTurn(
            firstSegment = { "  tell me about  " },
            tailSegment = tail.tailSegment,
        )
        // Single space between segments, no leading / trailing space.
        assertEquals("tell me about the weather", result)
    }

    @Test
    fun `treats blank-string tail as end-of-turn`() = runTest {
        val tail = ScriptedTail(listOf("   ", "\t\n"))
        val result = ListenAggregator.listenOneTurn(
            firstSegment = { "hello" },
            tailSegment = tail.tailSegment,
        )
        assertEquals("hello", result)
        // Stops on the first blank tail — doesn't consume the second.
        assertEquals(1, tail.calls)
    }
}
