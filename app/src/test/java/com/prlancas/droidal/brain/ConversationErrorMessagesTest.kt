package com.prlancas.droidal.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Tests that conversation-level exceptions get translated into
 * something Droidal can sensibly read aloud.
 *
 * The on-device LiteRT-LM JNI layer can throw multi-hundred-character
 * stack traces for tool-grammar failures; speaking those verbatim is
 * unusable. The mapping here is the only thing standing between those
 * raw errors and the user.
 */
class ConversationErrorMessagesTest {

    @Test
    fun `tool-grammar failure maps to a friendly tool apology`() {
        val msg = ConversationErrorMessages.friendly(
            RuntimeException("Failed to parse tool calls: <huge JNI dump...>"),
        )
        assertEquals(
            "Sorry, I got confused trying to use one of my tools. Could you try again?",
            msg,
        )
    }

    @Test
    fun `function-calling tool-grammar failure also maps to tool apology`() {
        val msg = ConversationErrorMessages.friendly(
            RuntimeException("Failed to parse FC tool calls"),
        )
        assertEquals(
            "Sorry, I got confused trying to use one of my tools. Could you try again?",
            msg,
        )
    }

    @Test
    fun `Status Code 3 maps to gibberish-output apology`() {
        val msg = ConversationErrorMessages.friendly(
            RuntimeException("LiteRT-LM ERROR Status Code: 3 — invalid output"),
        )
        assertEquals(
            "Sorry, my model returned something I couldn't read. Could you try again?",
            msg,
        )
    }

    @Test
    fun `unknown error message falls back to a generic apology`() {
        val msg = ConversationErrorMessages.friendly(
            RuntimeException("OutOfMemoryError"),
        )
        assertEquals(
            "Sorry, I hit a problem. Could you try again?",
            msg,
        )
    }

    @Test
    fun `null exception still produces something speakable`() {
        val msg = ConversationErrorMessages.friendly(null)
        assertNotNull(msg)
        assertEquals(
            "Sorry, I hit a problem. Could you try again?",
            msg,
        )
    }

    @Test
    fun `exception with no message also gets the generic apology`() {
        val msg = ConversationErrorMessages.friendly(RuntimeException())
        assertEquals(
            "Sorry, I hit a problem. Could you try again?",
            msg,
        )
    }
}
