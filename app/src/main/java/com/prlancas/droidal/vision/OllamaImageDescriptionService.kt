package com.prlancas.droidal.vision

import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Image description service using Ollama with LLaVA model
 */
class OllamaImageDescriptionService(
    private val host: String = "192.168.1.130",
    private val port: Int = 11434,
    private val model: String = "llava"
) : ImageDescriptionService() {
    
    override suspend fun describeImage(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        try {
            val base64Image = bitmapToBase64(bitmap)
            val url = URL("http://$host:$port/api/generate")
            
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            // Create request body for Ollama API
            val imagesArray = com.google.gson.JsonArray().apply {
                add(base64Image)
            }
            val requestBody = JsonObject().apply {
                addProperty("model", model)
                addProperty("prompt", "Describe what you see in this image in detail.")
                addProperty("stream", false)
                add("images", imagesArray)
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
                val description = jsonResponse.get("response")?.asString
                
                if (description != null) {
                    Log.d(logTag, "Image description successful")
                    return@withContext description.trim()
                } else {
                    Log.e(logTag, "No response field in Ollama response")
                    return@withContext null
                }
            } else {
                val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(logTag, "Ollama API error: $responseCode - $errorResponse")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error describing image with Ollama: ${e.message}", e)
            null
        }
    }
}

