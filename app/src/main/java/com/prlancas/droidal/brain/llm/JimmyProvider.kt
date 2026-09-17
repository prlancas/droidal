package com.prlancas.droidal.brain.llm

import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.prlancas.droidal.brain.tools.DroidalToolDispatcher
import com.prlancas.droidal.brain.tools.DroidalTools
import com.prlancas.droidal.brain.tools.TextToolSchema
import com.prlancas.droidal.debug.VerboseLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * LLM provider for chatjimmy.ai.
 *
 * Implements the direct API calls as seen in the cj2api reference project.
 * Supports tool calling by parsing text-based function calls from the reply.
 */
class JimmyProvider(
    private val modelName: String = "llama3.1-8B"
) : LlmProvider {

    override val displayName: String = "Jimmy ($modelName)"

    override val supportsImages: Boolean = false

    override fun newSession(systemPrompt: String, tools: DroidalTools): ChatSession {
        val fullPrompt = systemPrompt + "\n\n" + TextToolSchema.generate()
        VerboseLog.logProviderWire("Jimmy", "systemPrompt", fullPrompt)
        return JimmySession(modelName, fullPrompt, tools)
    }

    override suspend fun describeImage(bitmap: Bitmap, prompt: String): String? = null

    override fun close() {}

    companion object {
        const val API_URL = "https://chatjimmy.ai/api/chat"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}

private class JimmySession(
    private val modelName: String,
    private val systemPrompt: String,
    private val tools: DroidalTools
) : ChatSession {

    private val messages = JsonArray()

    override suspend fun send(userMessage: String): String = send(userMessage) { }

    override suspend fun send(userMessage: String, onPartial: (String) -> Unit): String = withContext(Dispatchers.IO) {
        messages.add(
            JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", userMessage)
            }
        )
        VerboseLog.logLlmRequest("Jimmy", modelName, userMessage)

        var lastReply = ""
        repeat(MAX_TOOL_ITERATIONS) {
            val reply = callJimmy(onPartial)
            lastReply = reply

            val toolCalls = parseToolCalls(reply)

            // Record a clean version of the assistant's reply (minus tool calls)
            // in history so future turns aren't cluttered with old syntax.
            val cleanAssistantReply = reply.replace(TOOL_CALL_PATTERN, "").trim()
            messages.add(
                JsonObject().apply {
                    addProperty("role", "assistant")
                    addProperty("content", cleanAssistantReply)
                }
            )

            if (toolCalls.isEmpty()) {
                VerboseLog.logLlmResponse("Jimmy", modelName, reply)
                return@withContext reply
            }

            val resultsParts = mutableListOf<String>()
            for (call in toolCalls) {
                Log.i(TAG, "Jimmy Tool call: ${call.name}(${call.args})")
                val result = DroidalToolDispatcher.invoke(tools, call.name, call.args)
                resultsParts.add("Tool ${call.name} returned: $result")
            }

            val resultsText = resultsParts.joinToString("\n")
            messages.add(
                JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", resultsText)
                }
            )
        }

        Log.w(TAG, "Hit tool-call iteration limit ($MAX_TOOL_ITERATIONS)")
        lastReply
    }

    private fun callJimmy(onPartial: (String) -> Unit): String {
        val payload = JsonObject().apply {
            add("messages", messages)
            add(
                "chatOptions",
                JsonObject().apply {
                    addProperty("selectedModel", modelName)
                    addProperty("systemPrompt", systemPrompt)
                    addProperty("topK", 8)
                }
            )
            add("attachment", null)
        }

        val connection = (URL(JimmyProvider.API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", JimmyProvider.USER_AGENT)
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
        }

        val fullReply = StringBuilder()
        return try {
            val body = Gson().toJson(payload)
            VerboseLog.logProviderWire("Jimmy", "request", body)
            OutputStreamWriter(connection.outputStream).use { it.write(body) }

            val code = connection.responseCode
            if (code !in 200..299) {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() }
                error("Jimmy HTTP $code: $err")
            }

            val reader = connection.inputStream.bufferedReader()
            val charBuffer = CharArray(256)
            var charsRead: Int
            var sawStats = false
            var pendingContent = ""
            val statsMarker = "<|stats|>"

            while (reader.read(charBuffer).also { charsRead = it } != -1) {
                val chunk = pendingContent + String(charBuffer, 0, charsRead)
                pendingContent = ""

                val statsIdx = chunk.indexOf(statsMarker)
                if (statsIdx != -1) {
                    val contentPart = chunk.substring(0, statsIdx)
                    appendAndStream(fullReply, contentPart, onPartial)
                    sawStats = true
                    break
                }

                val partialIdx = longestPrefixSuffix(chunk, statsMarker)
                val safeText = chunk.substring(0, chunk.length - partialIdx)
                pendingContent = chunk.substring(chunk.length - partialIdx)

                appendAndStream(fullReply, safeText, onPartial)
            }

            if (!sawStats) {
                appendAndStream(fullReply, pendingContent, onPartial)
            }

            fullReply.toString().trim()
        } finally {
            connection.disconnect()
        }
    }

    private fun appendAndStream(fullReply: StringBuilder, text: String, onPartial: (String) -> Unit) {
        if (text.isNotEmpty()) {
            fullReply.append(text)
            // Don't stream tool call syntax to the speaker
            val streamed = text.replace(TOOL_CALL_PATTERN, "").trim()
            if (streamed.isNotEmpty()) onPartial(streamed)
        }
    }

    private data class ToolCall(val name: String, val args: JsonObject)

    private fun parseToolCalls(text: String): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        val matches = TOOL_CALL_PATTERN.findAll(text)
        for (match in matches) {
            val name = match.groupValues[1]
            val argsRaw = match.groupValues[2]
            val args = JsonObject()
            ARG_PATTERN.findAll(argsRaw).forEach { argMatch ->
                val argName = argMatch.groupValues[1]
                val argVal = argMatch.groupValues[2].trim()
                if (argVal.startsWith("\"") && argVal.endsWith("\"")) {
                    args.addProperty(argName, argVal.substring(1, argVal.length - 1).replace("\\\"", "\""))
                } else if (argVal.toLongOrNull() != null) {
                    args.addProperty(argName, argVal.toLong())
                } else if (argVal.toDoubleOrNull() != null) {
                    args.addProperty(argName, argVal.toDouble())
                } else if (argVal == "true" || argVal == "false") {
                    args.addProperty(argName, argVal.toBoolean())
                } else {
                    args.addProperty(argName, argVal)
                }
            }
            calls.add(ToolCall(name, args))
        }
        return calls
    }

    /** Length of the longest suffix of [s] that's also a prefix of [marker]. */
    private fun longestPrefixSuffix(s: String, marker: String): Int {
        val limit = minOf(s.length, marker.length - 1)
        for (k in limit downTo 1) {
            if (marker.startsWith(s.substring(s.length - k))) return k
        }
        return 0
    }

    override fun close() {}

    companion object {
        private const val TAG = "JimmySession"
        private const val MAX_TOOL_ITERATIONS = 8
        private val TOOL_CALL_PATTERN = Regex("""(\w+)\(([^)]*)\)""")
        private val ARG_PATTERN = Regex("""(\w+)\s*=\s*("(?:[^"\\]|\\.)*"|[^,)]+)""")
    }
}
