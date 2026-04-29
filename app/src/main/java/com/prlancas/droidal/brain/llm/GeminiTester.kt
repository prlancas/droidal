package com.prlancas.droidal.brain.llm

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * One-shot probe used by the Settings UI's "Test" button to verify a Gemini
 * API key is valid without requiring the full Koog agent / tool registry.
 */
object GeminiTester {
    private const val TAG = "GeminiTester"

    suspend fun test(apiKey: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "Enter a key first"
        try {
            val url = URL(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:generateContent?key=$apiKey",
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
            }
            val body = JsonObject().apply {
                add(
                    "contents",
                    com.google.gson.JsonArray().apply {
                        add(JsonObject().apply {
                            add(
                                "parts",
                                com.google.gson.JsonArray().apply {
                                    add(JsonObject().apply { addProperty("text", "ping") })
                                },
                            )
                        })
                    },
                )
            }
            OutputStreamWriter(connection.outputStream).use { it.write(Gson().toJson(body)) }
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
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
