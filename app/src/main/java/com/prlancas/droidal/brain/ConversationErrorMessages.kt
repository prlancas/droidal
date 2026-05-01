package com.prlancas.droidal.brain

/**
 * Translates raw exceptions surfaced by [Agent.haveConversation] into
 * something Droidal can actually say without sounding like a build error.
 *
 * Specifically, LiteRT-LM tool-call grammar failures (the on-device model
 * invented a malformed tool call) come back as 500+ char JNI stack
 * traces; spoken aloud they're worse than useless. We flag the few
 * patterns we recognise and fall back to a generic apology for everything
 * else.
 *
 * Extracted from [Agent] so the mapping can be unit-tested without
 * spinning up the conversation loop.
 */
internal object ConversationErrorMessages {

    private const val TOOL_GRAMMAR_FAILURE =
        "Sorry, I got confused trying to use one of my tools. Could you try again?"

    private const val MODEL_GIBBERISH =
        "Sorry, my model returned something I couldn't read. Could you try again?"

    private const val GENERIC_FAILURE =
        "Sorry, I hit a problem. Could you try again?"

    fun friendly(t: Throwable?): String {
        val msg = t?.message.orEmpty()
        return when {
            "Failed to parse tool calls" in msg ||
                "Failed to parse FC tool calls" in msg ->
                TOOL_GRAMMAR_FAILURE
            "Status Code: 3" in msg ->
                MODEL_GIBBERISH
            else -> GENERIC_FAILURE
        }
    }
}
