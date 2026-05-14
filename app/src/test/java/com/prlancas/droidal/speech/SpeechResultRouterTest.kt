package com.prlancas.droidal.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the contract that prevents Droidal from hanging after a
 * voice-recognised debug command.
 *
 * The bug history: `SpeechToText.deliverResult` used to call
 * `DebugHandle.debugCommand(text)` for any "debug …" transcription
 * but forget to invoke `onComplete`. That left the agent's
 * `listenSuspend` stuck on a deferred that never completed,
 * `Agent.isChatting()` true forever, and the wake-word loop
 * suppressed — so once you ran a debug command Droidal never woke
 * again until the app was killed.
 *
 * The route policy now invariably calls `onComplete` exactly once,
 * with `null` on the debug branch so the agent treats it as "user
 * went quiet" and ends the conversation gracefully through its
 * normal finally block (which restarts wake-word detection).
 */
class SpeechResultRouterTest {

    @Test
    fun `non-debug text is forwarded to onComplete and onDebug is not called`() {
        var completed: String? = "<unset>"
        var completedCalls = 0
        var debugCalls = 0

        SpeechResultRouter.route(
            text = "What's the weather",
            onComplete = {
                completedCalls++
                completed = it
            },
            onDebug = { debugCalls++ },
        )

        assertEquals(1, completedCalls)
        assertEquals(0, debugCalls)
        assertEquals("What's the weather", completed)
    }

    @Test
    fun `debug command runs onDebug and unblocks onComplete with null`() {
        var debugSeen: String? = null
        var debugCalls = 0
        var completed: String? = "<unset>"
        var completedCalls = 0

        SpeechResultRouter.route(
            text = "debug echo",
            onComplete = {
                completedCalls++
                completed = it
            },
            onDebug = {
                debugCalls++
                debugSeen = it
            },
        )

        assertEquals("debug branch must dispatch", 1, debugCalls)
        assertEquals("debug echo", debugSeen)
        assertEquals(
            "onComplete must fire exactly once on the debug branch — " +
                "this is the bug-fix that lets the agent's listenSuspend resolve",
            1,
            completedCalls,
        )
        assertNull(
            "onComplete must be called with null so the agent's " +
                "patient-listen sees a blank turn and ends the conversation",
            completed,
        )
    }

    @Test
    fun `debug command match is case-insensitive`() {
        var debugCalls = 0
        var completedCalls = 0
        var completed: String? = "<unset>"

        SpeechResultRouter.route(
            text = "Debug Look Cute",
            onComplete = {
                completedCalls++
                completed = it
            },
            onDebug = { debugCalls++ },
        )

        assertEquals(1, debugCalls)
        assertEquals(1, completedCalls)
        assertNull(completed)
    }

    @Test
    fun `text that merely contains debug as a substring is forwarded to onComplete`() {
        // "debugger", "I'm a debugger" etc. must NOT be hijacked — only
        // a leading "debug" word is the command prefix.
        var completed: String? = null
        var debugCalls = 0

        SpeechResultRouter.route(
            text = "I am a debugger by trade",
            onComplete = { completed = it },
            onDebug = { debugCalls++ },
        )

        // Note: current contract is "starts with debug" so this still
        // routes to onComplete because the leading word is "I". This
        // test pins that downstream-of-prefix matches are not stolen.
        assertEquals(0, debugCalls)
        assertEquals("I am a debugger by trade", completed)
    }

    @Test
    fun `onComplete is called exactly once on every code path`() {
        // Belt-and-braces: the bug we are guarding against was missing
        // an onComplete call. Make sure both branches obey the
        // exactly-once rule and never invoke it twice either.
        listOf("hello", "debug ip", "Debug Hello", "DEBUG WHO", "tell me about life").forEach { text ->
            var calls = 0
            SpeechResultRouter.route(
                text = text,
                onComplete = { calls++ },
                onDebug = { /* no-op */ },
            )
            assertTrue("onComplete must fire for \"$text\" — was $calls", calls == 1)
        }
    }
}
