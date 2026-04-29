package com.prlancas.droidal.brain.llm

import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.prlancas.droidal.brain.tools.DroidalToolDispatcher
import com.prlancas.droidal.brain.tools.DroidalTools
import com.prlancas.droidal.brain.tools.GeminiToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Direct REST client for Gemini's `generateContent` endpoint, with the
 * function-calling loop handled in-line.
 *
 * Replaces Koog's `AIAgent` + `simpleGoogleAIExecutor` + `ToolRegistry`
 * stack. [DroidalTools] serves both this provider (via
 * [GeminiToolSchema]) and the LiteRT-LM provider, so there is exactly one
 * place to add a new tool.
 */
class GeminiRestProvider(
    private val apiKey: String,
    private val modelName: String = DEFAULT_MODEL,
) : LlmProvider {

    override val displayName: String = "Gemini ($modelName)"

    override val supportsImages: Boolean = true

    override fun newSession(systemPrompt: String, tools: DroidalTools): ChatSession =
        GeminiSession(apiKey, modelName, systemPrompt, tools)

    /**
     * Image description is already handled by [com.prlancas.droidal.vision.GeminiImageDescriptionService]
     * which [ImageDescriber] falls through to. We return null here so the
     * router keeps using that existing path rather than duplicating it.
     */
    override suspend fun describeImage(bitmap: Bitmap, prompt: String): String? = null

    override fun close() {}

    companion object {
        const val DEFAULT_MODEL = "gemini-2.0-flash-exp"
    }
}

private class GeminiSession(
    private val apiKey: String,
    private val modelName: String,
    systemPrompt: String,
    private val tools: DroidalTools,
) : ChatSession {

    private val systemInstruction: JsonObject = JsonObject().apply {
        add(
            "parts",
            JsonArray().apply {
                add(JsonObject().apply { addProperty("text", systemPrompt) })
            },
        )
    }

    /** Full rolling conversation in Gemini `Content[]` shape. */
    private val history = JsonArray()

    /** Pre-serialised tool declarations — cheap, so build once per session. */
    private val toolsJson = GeminiToolSchema.toolsJson()

    override suspend fun send(userMessage: String): String = withContext(Dispatchers.IO) {
        history.add(userContent(userMessage))

        repeat(MAX_TOOL_ITERATIONS) {
            val modelContent = callGemini()
            history.add(modelContent)

            val parts = modelContent.getAsJsonArray("parts") ?: JsonArray()
            val functionCalls = parts.mapNotNull { it.asJsonObject.getAsJsonObject("functionCall") }

            if (functionCalls.isEmpty()) {
                return@withContext extractText(parts)
            }

            // Execute every function call the model emitted this turn and
            // append them as a single follow-up "user" turn (this is what
            // Gemini's function-calling protocol expects).
            val responseParts = JsonArray()
            for (fc in functionCalls) {
                val name = fc.get("name").asString
                val args = fc.getAsJsonObject("args") ?: JsonObject()
                Log.i(TAG, "Tool call: $name(${args})")
                val result = DroidalToolDispatcher.invoke(tools, name, args)
                responseParts.add(
                    JsonObject().apply {
                        add(
                            "functionResponse",
                            JsonObject().apply {
                                addProperty("name", name)
                                add("response", mapToJson(result))
                            },
                        )
                    },
                )
            }
            history.add(
                JsonObject().apply {
                    addProperty("role", "user")
                    add("parts", responseParts)
                },
            )
        }

        Log.w(TAG, "Hit tool-call iteration limit ($MAX_TOOL_ITERATIONS)")
        "I got stuck calling tools. Let's try something else."
    }

    override fun close() {}

    private fun callGemini(): JsonObject {
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey",
        )
        val payload = JsonObject().apply {
            add("systemInstruction", systemInstruction)
            add("contents", history)
            add("tools", toolsJson)
        }
        val json = Gson().toJson(payload)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
        }
        try {
            OutputStreamWriter(connection.outputStream).use { it.write(json) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw RuntimeException("Gemini HTTP $code: $err")
            }
            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            return parseModelContent(raw)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseModelContent(raw: String): JsonObject {
        val root = JsonParser.parseString(raw).asJsonObject
        val candidates = root.getAsJsonArray("candidates")
            ?: throw RuntimeException("Gemini returned no candidates: $raw")
        if (candidates.size() == 0) throw RuntimeException("Gemini returned empty candidates")
        val content = candidates[0].asJsonObject.getAsJsonObject("content")
            ?: throw RuntimeException("Gemini candidate missing content: $raw")
        // Force role=model so the history stays well-formed even if Gemini
        // omits it (rare but observed on some error responses).
        if (!content.has("role")) content.addProperty("role", "model")
        return content
    }

    private fun userContent(message: String): JsonObject = JsonObject().apply {
        addProperty("role", "user")
        add(
            "parts",
            JsonArray().apply {
                add(JsonObject().apply { addProperty("text", message) })
            },
        )
    }

    private fun extractText(parts: JsonArray): String =
        parts.asSequence()
            .mapNotNull { it.asJsonObject.get("text")?.asString }
            .joinToString("\n")
            .trim()

    private fun mapToJson(map: Map<String, Any>): JsonObject = JsonObject().apply {
        for ((k, v) in map) add(k, anyToJson(v))
    }

    private fun anyToJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull.INSTANCE
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject().apply {
            for ((k, v) in value) add(k.toString(), anyToJson(v))
        }
        is Collection<*> -> JsonArray().apply {
            for (v in value) add(anyToJson(v))
        }
        else -> JsonPrimitive(value.toString())
    }

    companion object {
        private const val TAG = "GeminiSession"
        private const val MAX_TOOL_ITERATIONS = 8
    }
}
