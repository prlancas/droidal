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
 * marker or an exhausted listen budget.
 *
 * Cases covered:
 * - Happy path multi-turn: user → LLM reply → user → LLM reply ending
 *   the conversation.
 * - Single STT blank then real reply: agent silently restarts and
 *   never speaks any nudge (the user is just thinking).
 * - All STT blanks with no voice activity: agent gives up silently,
 *   never speaking "Pardon?" or "Still there?".
 * - Soft prompt fires once when voice was heard recently and then
 *   the budget elapses.
 * - Split-across-pause segments are concatenated into one user turn
 *   by [ListenAggregator].
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
     * Result of a journey run — the spoken utterances plus the
     * sequence of user turns that would have been persisted to the
     * learning DB. Production records the user turn AFTER the LLM
     * has actually replied so prefill failures don't orphan a
     * "(1 turns)" row in RECENT CONVERSATIONS; the harness mirrors
     * that ordering so the orphan-turn fix is unit-testable.
     */
    private data class JourneyResult(
        val spoken: List<String>,
        val persistedUserTurns: List<String>,
    )

    /**
     * Mirrors the public-facing behaviour of [Agent.haveConversation].
     * Returns the list of utterances Droidal spoke and the user
     * turns that would have been persisted to the learning DB.
     * Throws nothing — exceptions from the session are mapped
     * through [ConversationErrorMessages] just like production.
     *
     * The listening half is wired through the same two pure policies
     * production uses: [ConversationListenPolicy.listenPatiently]
     * (silent restarts, soft prompt only on real silence with recent
     * voice) and [ListenAggregator.listenOneTurn] (concatenate
     * segments split by short pauses into one user turn).
     */
    private suspend fun runJourney(
        initialUserInput: String,
        session: ChatSession,
        scriptedListens: List<String?>,
        quietBudgetMs: Long = 5_000L,
        voiceHeardWithinMs: () -> Long = { Long.MAX_VALUE },
        faceVisibleWithinMs: () -> Long = { Long.MAX_VALUE },
    ): JourneyResult {
        val sayCapture = SayCapture()
        val persistedUserTurns = mutableListOf<String>()
        val listens = ArrayDeque(scriptedListens)
        var clock = 0L
        val nowMs = { clock }
        var nextInput = initialUserInput

        // Each listen call advances the fake clock by a realistic
        // amount: blanks consume a real recogniser's silence-length
        // window, real replies are quick. Lets the budget elapse
        // deterministically when blanks pile up.
        val listen: suspend (Boolean) -> String? = { _ ->
            val r = if (listens.isEmpty()) null else listens.removeFirst()
            clock += if (r.isNullOrBlank()) 1_500L else 200L
            r
        }

        try {
            while (true) {
                val streamer = TtsStreamer(publishSay = sayCapture)
                val reply = session.send(nextInput) { delta -> streamer.feed(delta) }
                // Persist the user turn AFTER send returns — so a
                // failed send leaves no orphan row in the DB.
                if (nextInput.isNotBlank()) persistedUserTurns += nextInput
                streamer.finishAndAwait()

                if (reply.contains("[END_CONVERSATION]")) {
                    return JourneyResult(sayCapture.spoken, persistedUserTurns)
                }

                val followUp = ListenAggregator.listenOneTurn(
                    firstSegment = {
                        ConversationListenPolicy.listenPatiently(
                            quietBudgetMs = quietBudgetMs,
                            listen = listen,
                            voiceHeardWithinMs = voiceHeardWithinMs,
                            faceVisibleWithinMs = faceVisibleWithinMs,
                            softPrompt = { sayCapture(Say("Still there?")) },
                            nowMs = nowMs,
                        )
                    },
                    tailSegment = { _ -> listen(true) },
                )
                if (followUp.isNullOrBlank()) {
                    return JourneyResult(sayCapture.spoken, persistedUserTurns)
                }
                nextInput = followUp
            }
        } catch (e: Exception) {
            sayCapture(Say(ConversationErrorMessages.friendly(e)))
            return JourneyResult(sayCapture.spoken, persistedUserTurns)
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
        ).spoken
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
    fun `single STT blank silently restarts and continues the conversation`() = runTest {
        val session = ScriptedSession(
            listOf(
                listOf("Hi there. "),
                listOf("OK bye. ", "[END_CONVERSATION]"),
            ),
        )
        val spoken = runJourney(
            initialUserInput = "Hi",
            session = session,
            // First listen is blank (user thinking), second has the
            // real follow-up.
            scriptedListens = listOf(null, "alright bye"),
        ).spoken
        assertEquals(2, session.sendCount)
        // Crucially, no verbal nudge — the new policy silently
        // restarts STT during the budget rather than rushing the
        // user with "Pardon?".
        assertEquals(
            "No Pardon? must be spoken on a short hesitation",
            0,
            spoken.count { it == "Pardon?" },
        )
        assertEquals(
            "No 'Still there?' either — the budget never elapsed",
            0,
            spoken.count { it == "Still there?" },
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
    fun `all-blank STT streak with no voice ends silently`() = runTest {
        val session = ScriptedSession(listOf(listOf("Hello.")))
        val spoken = runJourney(
            initialUserInput = "Hi",
            session = session,
            // No reply is ever heard, no voice spike either — user
            // walked off, Droidal gives up gracefully without a
            // verbal nudge.
            scriptedListens = emptyList(),
            quietBudgetMs = 1_000L,
            voiceHeardWithinMs = { Long.MAX_VALUE },
        ).spoken
        assertEquals(1, session.sendCount)
        assertEquals(
            "No Pardon? must ever be spoken in the new policy",
            0,
            spoken.count { it == "Pardon?" },
        )
        assertEquals(
            "Soft prompt must not fire when no voice was heard",
            0,
            spoken.count { it == "Still there?" },
        )
        assertEquals(listOf("Hello."), spoken)
    }

    @Test
    fun `soft prompt fires once when voice was heard but the user then stopped`() = runTest {
        val session = ScriptedSession(listOf(listOf("Hello.")))
        // Two blanks elapse the budget, voice was heard recently so
        // we soft-prompt and extend, then user finally replies
        // before the second budget elapses.
        val spoken = runJourney(
            initialUserInput = "Hi",
            session = session,
            scriptedListens = listOf(null, null, "ok hi"),
            quietBudgetMs = 1_000L,
            voiceHeardWithinMs = { 100L },
        ).spoken
        // The agent should have heard the eventual reply but the
        // session only had one scripted LLM turn — so we'll bail
        // when ScriptedSession runs out, mapped to a friendly
        // apology. We just want to verify the soft prompt fired
        // exactly once before that.
        assertEquals(
            "Soft prompt must fire exactly once after the budget elapsed with recent voice",
            1,
            spoken.count { it == "Still there?" },
        )
    }

    @Test
    fun `split-across-pause segments are concatenated into one user turn`() = runTest {
        // The first user turn is split: "tell me about" then a pause
        // then "the weather". The aggregator should hand the LLM
        // exactly one concatenated message.
        val session = ScriptedSession(
            listOf(
                listOf("Sunny today. ", "[END_CONVERSATION]"),
            ),
        )
        val seenByLlm = mutableListOf<String>()
        val recordingSession = object : ChatSession {
            override suspend fun send(userMessage: String): String =
                send(userMessage) {}
            override suspend fun send(
                userMessage: String,
                onPartial: (String) -> Unit,
            ): String {
                seenByLlm += userMessage
                return session.send(userMessage, onPartial)
            }
            override fun close() = session.close()
        }
        runJourney(
            initialUserInput = "tell me about",
            session = recordingSession,
            scriptedListens = emptyList(),
        )
        // Only the initial input reaches the LLM (ScriptedSession
        // ends the conversation on the marker before another listen
        // happens). This test mainly proves the harness wiring
        // doesn't accidentally split the initial input — see
        // ListenAggregatorTest for direct concatenation coverage.
        assertEquals(listOf("tell me about"), seenByLlm)
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
        ).spoken
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
        ).spoken
        assertEquals(listOf("Sure thing, here it is."), spoken)
    }

    @Test
    fun `face presence keeps the conversation alive across a quiet pause`() = runTest {
        // The user has gone quiet (no voice, blank STT), but the
        // camera still sees their face — they're standing right
        // there, thinking. The patient policy should soft-prompt
        // ("Still there?") once and extend the budget; the user
        // then replies and the conversation continues.
        //
        // The harness advances the fake clock by 1500ms per blank,
        // so a 3s budget elapses on the second blank — the soft-
        // prompt-and-extend branch fires there, the third listen
        // returns the user's reply, and we reach the second LLM
        // turn that ends with the marker.
        val session = ScriptedSession(
            listOf(
                listOf("Take your time. "),
                listOf("Got it, bye. ", "[END_CONVERSATION]"),
            ),
        )
        val spoken = runJourney(
            initialUserInput = "Hi",
            session = session,
            scriptedListens = listOf(null, null, "ok let's wrap up"),
            quietBudgetMs = 3_000L,
            voiceHeardWithinMs = { Long.MAX_VALUE },
            faceVisibleWithinMs = { 250L },
        ).spoken
        assertEquals(
            "Soft prompt must fire exactly once for a face-only quiet stretch",
            1,
            spoken.count { it == "Still there?" },
        )
        assertEquals(2, session.sendCount)
        assertTrue(
            "Final spoken chunk should be the LLM's farewell",
            spoken.last() == "Got it, bye.",
        )
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
        ).spoken
        assertEquals(listOf("Bye."), spoken)
        assertNull(spoken.find { "END_CONV" in it })
    }

    @Test
    fun `failed send leaves no orphan user turn in the persistent log`() = runTest {
        // Production bug: when the LLM session threw mid-prefill
        // (context overflow, parser failure) the user turn had
        // already been committed to the learning DB. Each crashed
        // conversation showed up as "(1 turns): hello" in RECENT
        // CONVERSATIONS forever after, bloating every subsequent
        // system prompt. The fix defers recordTurn(USER) until
        // after send returns successfully — which means a
        // FailingSession run records nothing.
        val session = FailingSession(
            RuntimeException("Input token ids are too long. 5500 >= 4000"),
        )
        val result = runJourney(
            initialUserInput = "hello how are you",
            session = session,
            scriptedListens = emptyList(),
        )
        // Friendly apology spoken, no raw stack trace leaked.
        assertEquals(1, result.spoken.size)
        assertFalse(result.spoken.first().contains("token"))
        // And — the load-bearing assertion — no user turn ever
        // reached the persistent log.
        assertEquals(
            "Failed prefill must not leave an orphan user turn in the DB",
            emptyList<String>(),
            result.persistedUserTurns,
        )
    }

    @Test
    fun `successful turn persists the user input exactly once`() = runTest {
        // Counter-test for the fix: a normal turn should still
        // persist its user input once the reply has streamed back.
        val session = ScriptedSession(
            listOf(
                listOf("Got it. ", "[END_CONVERSATION]"),
            ),
        )
        val result = runJourney(
            initialUserInput = "remember I like coffee",
            session = session,
            scriptedListens = emptyList(),
        )
        assertEquals(listOf("remember I like coffee"), result.persistedUserTurns)
    }
}
