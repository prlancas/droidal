package com.prlancas.droidal.brain.llm

import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.prlancas.droidal.brain.tools.DroidalToolDispatcher
import com.prlancas.droidal.brain.tools.DroidalTools
import com.prlancas.droidal.brain.tools.OpenAIToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * LLM provider backed by [OpenRouter](https://openrouter.ai) — an
 * OpenAI-compatible aggregation endpoint that gives access to GPT-4o,
 * Claude, Llama, DeepSeek, etc. through a single API key.
 *
 * Uses the standard OpenAI Chat Completions format, so tool calling is
 * wired with [OpenAIToolSchema] + [DroidalToolDispatcher] (the same
 * dispatcher powering [GeminiRestProvider] — tools are defined once on
 * [DroidalTools]).
 *
 * The `HTTP-Referer` / `X-Title` headers are the OpenRouter convention for
 * attributing traffic to a specific app and are optional.
 */
class OpenRouterProvider(
    private val apiKey: String,
    private val modelName: String,
) : LlmProvider {

    override val displayName: String = "OpenRouter ($modelName)"

    override val supportsImages: Boolean = false

    override fun newSession(systemPrompt: String, tools: DroidalTools): ChatSession =
        OpenRouterSession(apiKey, modelName, systemPrompt, tools)

    override suspend fun describeImage(bitmap: Bitmap, prompt: String): String? = null

    override fun close() {}

    companion object {
        const val API_URL = "https://openrouter.ai/api/v1/chat/completions"
    }
}

private class OpenRouterSession(
    private val apiKey: String,
    private val modelName: String,
    systemPrompt: String,
    private val tools: DroidalTools,
) : ChatSession {

    private val messages = JsonArray().apply {
        add(
            JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", systemPrompt)
            },
        )
    }

    private val toolsJson = OpenAIToolSchema.toolsJson()

    override suspend fun send(userMessage: String): String = withContext(Dispatchers.IO) {
        messages.add(
            JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", userMessage)
            },
        )

        repeat(MAX_TOOL_ITERATIONS) {
            val assistant = callOpenRouter()
            messages.add(assistant)

            val toolCalls = assistant.getAsJsonArray("tool_calls")
            if (toolCalls == null || toolCalls.size() == 0) {
                return@withContext assistant.get("content")
                    ?.takeIf { !it.isJsonNull }
                    ?.asString
                    ?.trim()
                    .orEmpty()
            }

            for (tc in toolCalls) {
                val obj = tc.asJsonObject
                val id = obj.get("id")?.asString ?: ""
                val fn = obj.getAsJsonObject("function")
                val name = fn.get("name").asString
                // OpenAI returns `arguments` as a stringified JSON blob,
                // not a JSON object — a historical quirk of the schema.
                val argsRaw = fn.get("arguments")?.asString.orEmpty()
                val argsObj = runCatching {
                    JsonParser.parseString(argsRaw).asJsonObject
                }.getOrElse { JsonObject() }
                Log.i(TAG, "Tool call: $name($argsObj)")
                val result = DroidalToolDispatcher.invoke(tools, name, argsObj)
                messages.add(
                    JsonObject().apply {
                        addProperty("role", "tool")
                        addProperty("tool_call_id", id)
                        addProperty("content", Gson().toJson(result))
                    },
                )
            }
        }

        Log.w(TAG, "Hit tool-call iteration limit ($MAX_TOOL_ITERATIONS)")
        "I got stuck calling tools. Let's try something else."
    }

    override fun close() {}

    private fun callOpenRouter(): JsonObject {
        val payload = JsonObject().apply {
            addProperty("model", modelName)
            add("messages", messages)
            add("tools", toolsJson)
        }
        val connection = (URL(OpenRouterProvider.API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            // OpenRouter's attribution headers — optional but polite.
            setRequestProperty("HTTP-Referer", "https://github.com/prlancas/droidal")
            setRequestProperty("X-Title", "Droidal")
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
        }
        try {
            OutputStreamWriter(connection.outputStream).use { it.write(Gson().toJson(payload)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw RuntimeException("OpenRouter HTTP $code: $err")
            }
            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JsonParser.parseString(raw).asJsonObject
            val choices = root.getAsJsonArray("choices")
                ?: throw RuntimeException("OpenRouter returned no choices: $raw")
            if (choices.size() == 0) throw RuntimeException("OpenRouter returned empty choices")
            return choices[0].asJsonObject.getAsJsonObject("message")
                ?: throw RuntimeException("OpenRouter choice missing message: $raw")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "OpenRouterSession"
        private const val MAX_TOOL_ITERATIONS = 8
    }
}
