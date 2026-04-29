package com.prlancas.droidal.brain.llm

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * One-shot probe used by the Settings UI's "Test" button to verify an
 * OpenRouter API key + model slug are valid (HTTP 200) without running a
 * full chat session.
 */
object OpenRouterTester {

    private const val TAG = "OpenRouterTester"

    suspend fun test(apiKey: String, model: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "Enter a key first"
        if (model.isBlank()) return@withContext "Enter a model"
        try {
            val body = JsonObject().apply {
                addProperty("model", model)
                add(
                    "messages",
                    JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("role", "user")
                                addProperty("content", "ping")
                            },
                        )
                    },
                )
                addProperty("max_tokens", 1)
            }
            val connection = (URL(OpenRouterProvider.API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("HTTP-Referer", "https://github.com/prlancas/droidal")
                setRequestProperty("X-Title", "Droidal")
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
            }
            OutputStreamWriter(connection.outputStream).use { it.write(Gson().toJson(body)) }
            val code = connection.responseCode
            if (code in 200..299) {
                "OK"
            } else {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "Test failed $code: $err")
                "Failed ($code)"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Test error", e)
            "Error: ${e.message}"
        }
    }
}
