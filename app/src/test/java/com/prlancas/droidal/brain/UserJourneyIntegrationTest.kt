package com.prlancas.droidal.brain

import com.prlancas.droidal.brain.llm.ChatSession
import com.prlancas.droidal.event.events.Say
import com.prlancas.droidal.speech.TtsStreamer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end-style tests for the conversation user-journey, with the
 * external IO (LLM and STT) stubbed out so the test can run fast and
 * deterministic on the JVM.
 *
 * The harness here is a deliberately small re-creation of the loop in
 * [Agent.haveConversation] — just the bits that matter for the user
 * journey: send a turn, stream the reply through [TtsStreamer], decide
 * whether to listen again, and end on either the [END_CONVERSATION]
 * marker or an exhausted retry streak.
 *
 * Cases covered:
 * - Happy path multi-turn: user → LLM reply → user → LLM reply ending
 *   the conversation.
 * - Single STT blank then real reply: agent retries silently after
 *   speaking a single "Pardon?".
 * - All STT blanks: agent gives up after the retry cap, never speaking
 *   "Pardon?" more than once.
 * - LLM signals end with the [END_CONVERSATION] marker — the marker
 *   never reaches TTS.
 * - LLM throws — the friendly error message is spoken (not the JNI
 *   stack trace).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserJourneyIntegrationTest {

    /**
     * Scriptable fake LLM session. Each scripted reply is a list of
     * deltas (so we can simulate a streamed token-by-token reply); the
     * concatenated content is also returned from `send`.
     */
    private class ScriptedSession(replies: List<List<String>>) : ChatSession {
        private val pending = ArrayDeque(replies)
        var sendCount: Int = 0
            private set

        override suspend fun send(userMessage: String): String =
            send(userMessage) {}

        override suspend fun send(userMessage: String, onPartial: (String) -> Unit): String {
            sendCount++
            if (pending.isEmpty()) {
                throw IllegalStateException("ScriptedSession ran out of replies")
            }
            val deltas = pending.removeFirst()
            deltas.forEach(onPartial)
            return deltas.joinToString("")
        }

        override fun close() {}
    }

    private class FailingSession(private val toThrow: Throwable) : ChatSession {
        override suspend fun send(userMessage: String): String = throw toThrow
        override suspend fun send(
            userMessage: String,
            onPartial: (String) -> Unit,
        ): String = throw toThrow
        override fun close() {}
    }

    private class SayCapture : (Say) -> Unit {
        val spoken = mutableListOf<String>()
        override fun invoke(say: Say) {
            spoken += say.sentence
            say.onComplete?.invoke()
        }
    }

    /**
     * Mirrors the public-facing behaviour of [Agent.haveConversation].
     * Returns the list of utterances Droidal spoke during the journey
     * for inspection. Throws nothing — exceptions from the session are
     * mapped through [ConversationErrorMessages] just like production.
     */
    private suspend fun runJourney(
        initialUserInput: String,
        session: ChatSession,
        scriptedListens: List<String?>,
        maxBlankRetries: Int = 30,
    ): List<String> {
        val sayCapture = SayCapture()
        val listens = ArrayDeque(scriptedListens)
        var nextInput = initialUserInput

        try {
            while (true) {
                val streamer = TtsStreamer(publishSay = sayCapture)
                val reply = session.send(nextInput) { delta -> streamer.feed(delta) }
                streamer.finishAndAwait()

                if (reply.contains("[END_CONVERSATION]")) return sayCapture.spoken

                val followUp = ConversationListenPolicy.listenWithRetry(
                    maxBlankRetries = maxBlankRetries,
                    listen = { _ ->
                        if (listens.isEmpty()) null else listens.removeFirst()
                    },
                    say = { sayCapture(Say(it)) },
                )
                if (followUp.isNullOrBlank()) return sayCapture.spoken
                nextInput = followUp
            }
        } catch (e: Exception) {
            sayCapture(Say(ConversationErrorMessages.friendly(e)))
            return sayCapture.spoken
        }
    }

    @Test
    fun `multi-turn happy path ends on END_CONVERSATION marker`() = runTest {
        val session = ScriptedSession(
            listOf(
                // First reply arrives in two deltas — the streamer
                // flushes each completed sentence as it sees one.
                listOf("Hello! ", "How can I help? "),
                listOf("Goodbye, talk soon. ", "[END_CONVERSATION]"),
            ),
        )
        val spoken = runJourney(
            initialUserInput = "Hi droidal",
            session = session,
            scriptedListens = listOf("Bye for now"),
        )
        // Two LLM turns, one user turn between them, no Pardon, no
        // marker leak.
        assertEquals(2, session.sendCount)
        assertEquals(
            listOf("Hello!", "How can I help?", "Goodbye, talk soon."),
            spoken,
        )
        for (s in spoken) {
            assertFalse(
                "[END_CONVERSATION] must never reach TTS in '$s'",
                s.contains("END_CONVERSATION"),
            )
        }
    }

    @Test
    fun `single STT blank still recovers and continues the conversation`() = runTest {
        val session = ScriptedSession(
            listOf(
                listOf("Hi there. "),
                listOf("OK bye. ", "[END_CONVERSATION]"),
            ),
        )
        val spoken = runJourney(
            initialUserInput = "Hi",
            session = session,
            // First listen is blank, second has the real follow-up.
            scriptedListens = listOf(null, "alright bye"),
        )
        assertEquals(2, session.sendCount)
        // Pardon must appear exactly once (after the first blank).
        assertEquals(
            "Exactly one Pardon? per blank streak",
            1,
            spoken.count { it == "Pardon?" },
        )
        assertTrue(
            "First spoken chunk should be the LLM's first reply",
            spoken[0] == "Hi there.",
        )
        assertTrue(
            "Conversation should have ended on the marker (no further turns)",
            spoken.last() == "OK bye.",
        )
    }

    @Test
    fun `all-blank STT streak ends the conversation politely`() = runTest {
        val session = ScriptedSession(listOf(listOf("Hello.")))
        val spoken = runJourney(
            initialUserInput = "Hi",
            session = session,
            // Every listen will be blank — the listener pool is empty
            // so we'll consume `null` until the cap.
            scriptedListens = emptyList(),
            maxBlankRetries = 3,
        )
        // Single LLM reply, single Pardon, no further LLM turns.
        assertEquals(1, session.sendCount)
        assertEquals(
            "Pardon? must be spoken at most once even on a long blank streak",
            1,
            spoken.count { it == "Pardon?" },
        )
        assertEquals(listOf("Hello.", "Pardon?"), spoken)
    }

    @Test
    fun `LLM exception is converted to a friendly spoken apology`() = runTest {
        val session = FailingSession(
            RuntimeException("Failed to parse tool calls: <huge JNI dump>"),
        )
        val spoken = runJourney(
            initialUserInput = "Hi",
            session = session,
            scriptedListens = emptyList(),
        )
        // Exactly one spoken chunk — the friendly apology — and no
        // raw "Failed to parse..." text leaked to TTS.
        assertEquals(1, spoken.size)
        assertEquals(
            "Sorry, I got confused trying to use one of my tools. Could you try again?",
            spoken.first(),
        )
        assertFalse(spoken.first().contains("JNI"))
        assertFalse(spoken.first().contains("Failed to parse"))
    }

    @Test
    fun `marker that arrives mid-stream truncates spoken reply at the boundary`() = runTest {
        val session = ScriptedSession(
            listOf(
                listOf(
                    "Sure thing, here it is. ",
                    "[END_CONVERSATION]",
                    " — and this should never be spoken.",
                ),
            ),
        )
        val spoken = runJourney(
            initialUserInput = "Tell me one thing then stop",
            session = session,
            scriptedListens = emptyList(),
        )
        assertEquals(listOf("Sure thing, here it is."), spoken)
    }

    @Test
    fun `END_CONVERSATION is honoured even when split across deltas`() = runTest {
        val session = ScriptedSession(
            listOf(
                listOf("Bye. ", "[END_CONV", "ERSATION] tail"),
            ),
        )
        val spoken = runJourney(
            initialUserInput = "Bye",
            session = session,
            scriptedListens = emptyList(),
        )
        assertEquals(listOf("Bye."), spoken)
        assertNull(spoken.find { "END_CONV" in it })
    }
}
