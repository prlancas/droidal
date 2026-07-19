package com.prlancas.droidal.vision

import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Image description service using Google Gemini API
 */
class GeminiImageDescriptionService : ImageDescriptionService() {
    
    override suspend fun describeImage(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        try {
            val apiKey = SettingsRepository.get(Config.getContext()).geminiKey()
            if (apiKey.isNullOrEmpty()) {
                Log.e(logTag, "Gemini API key not configured — set one in Settings")
                return@withContext null
            }
            
            val base64Image = bitmapToBase64(bitmap)
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:generateContent?key=$apiKey")
            
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            // Create request body for Gemini API
            val inlineData = JsonObject().apply {
                addProperty("mime_type", "image/jpeg")
                addProperty("data", base64Image)
            }
            val requestBody = JsonObject().apply {
                add("contents", com.google.gson.JsonArray().apply {
                    add(JsonObject().apply {
                        add("parts", com.google.gson.JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("text", "Describe what you see in this image in detail.")
                            })
                            add(JsonObject().apply {
                                add("inline_data", inlineData)
                            })
                        })
                    })
                })
            }
            
            // Write request body
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(Gson().toJson(requestBody))
                writer.flush()
            }
            
            // Read response
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = Gson().fromJson(response, JsonObject::class.java)
                
                val candidates = jsonResponse.getAsJsonArray("candidates")
                if (candidates != null && candidates.size() > 0) {
                    val candidate = candidates[0].asJsonObject
                    val content = candidate.getAsJsonObject("content")
                    val parts = content.getAsJsonArray("parts")
                    if (parts != null && parts.size() > 0) {
                        val text = parts[0].asJsonObject.get("text")?.asString
                        if (text != null) {
                            Log.d(logTag, "Image description successful")
                            return@withContext text.trim()
                        }
                    }
                }
                
                Log.e(logTag, "Unexpected Gemini response format")
                return@withContext null
            } else {
                val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(logTag, "Gemini API error: $responseCode - $errorResponse")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error describing image with Gemini: ${e.message}", e)
            null
        }
    }
}

