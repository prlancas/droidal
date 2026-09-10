package com.prlancas.droidal.brain.llm

import android.graphics.Bitmap
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.prlancas.droidal.brain.tools.DroidalTools
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
 * Does not support vision or native tool calling.
 */
class JimmyProvider(
    private val modelName: String = "llama3.1-8B"
) : LlmProvider {

    override val displayName: String = "Jimmy ($modelName)"

    override val supportsImages: Boolean = false

    override fun newSession(systemPrompt: String, tools: DroidalTools): ChatSession {
        VerboseLog.logProviderWire("Jimmy", "systemPrompt", systemPrompt)
        return JimmySession(modelName, systemPrompt)
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
        try {
            val body = Gson().toJson(payload)
            VerboseLog.logProviderWire("Jimmy", "request", body)
            OutputStreamWriter(connection.outputStream).use { it.write(body) }

            val code = connection.responseCode
            if (code !in 200..299) {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() }
                error("Jimmy HTTP $code: $err")
            }

            // Read the stream chunk by chunk. Even if upstream doesn't
            // naturally stream SSE, reading in chunks lets us push text
            // to TTS as soon as we see it.
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
                    if (contentPart.isNotEmpty()) {
                        fullReply.append(contentPart)
                        onPartial(contentPart)
                    }
                    sawStats = true
                    break
                }

                // Hold back any suffix that could be the start of the stats marker.
                val partialIdx = longestPrefixSuffix(chunk, statsMarker)
                val safeText = chunk.substring(0, chunk.length - partialIdx)
                pendingContent = chunk.substring(chunk.length - partialIdx)

                if (safeText.isNotEmpty()) {
                    fullReply.append(safeText)
                    onPartial(safeText)
                }
            }

            // If we never saw stats, any pending content was real text.
            if (!sawStats && pendingContent.isNotEmpty()) {
                fullReply.append(pendingContent)
                onPartial(pendingContent)
            }

            val finalReply = fullReply.toString().trim()
            messages.add(
                JsonObject().apply {
                    addProperty("role", "assistant")
                    addProperty("content", finalReply)
                }
            )
            VerboseLog.logLlmResponse("Jimmy", modelName, finalReply)
            finalReply
        } finally {
            connection.disconnect()
        }
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
}
