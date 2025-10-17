package com.prlancas.droidal.llm

import android.util.Log
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.SendToLLM
import com.prlancas.droidal.event.events.Say
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

@OptIn(DelicateCoroutinesApi::class)
class LLMHandler {
    private val scope = MainScope()
    private val llmUrl = "http://192.168.1.130:8080/message"
    
    init {
        scope.launch(newSingleThreadContext("LLMThread")) {
            EventBus.subscribe<SendToLLM> { event ->
                sendToLLM(event.message)
            }
        }
    }
    
    private fun sendToLLM(message: String) {
        scope.launch(newSingleThreadContext("LLMThread")) {
            try {
                Log.d("LLM_HANDLER", "Sending message to LLM: $message")
                
                val url = URL(llmUrl)
                val connection = url.openConnection() as HttpURLConnection
                
                // Set connection timeout
                connection.connectTimeout = 10000 // 10 seconds
                connection.readTimeout = 30000 // 30 seconds
                
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.doOutput = true
                connection.doInput = true
                
                // Create JSON payload
                val jsonPayload = """{"message": "$message"}"""
                
                // Send request
                val outputStream = connection.outputStream
                val writer = OutputStreamWriter(outputStream)
                writer.write(jsonPayload)
                writer.flush()
                writer.close()
                
                // Get response
                val responseCode = connection.responseCode
                Log.d("LLM_HANDLER", "LLM response code: $responseCode")
                
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    Log.d("LLM_HANDLER", "LLM response: $response")
                    
                    // Parse response and speak it back
                    val responseMessage = parseLLMResponse(response)
                    EventBus.blockPublish(Say(responseMessage))
                } else {
                    val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                    Log.e("LLM_HANDLER", "LLM error response: $errorResponse")
                    EventBus.blockPublish(Say("LLM request failed with code: $responseCode"))
                }
                
                connection.disconnect()
                
            } catch (e: Exception) {
                Log.e("LLM_HANDLER", "Error sending to LLM: ${e.message}")
                EventBus.blockPublish(Say("Failed to connect to LLM: ${e.message}"))
            }
        }
    }
    
    private fun parseLLMResponse(response: String): String {
        return try {
            // Try to extract message from JSON response
            // This is a simple parser - you might need to adjust based on your LLM's response format
            if (response.contains("\"message\"")) {
                val startIndex = response.indexOf("\"message\":\"") + 11
                val endIndex = response.indexOf("\"", startIndex)
                if (startIndex in 11 until endIndex) {
                    response.substring(startIndex, endIndex)
                } else {
                    "LLM responded but couldn't parse message"
                }
            } else {
                // If no JSON structure, return the raw response
                response.take(200) // Limit to 200 characters
            }
        } catch (e: Exception) {
            Log.e("LLM_HANDLER", "Error parsing LLM response: ${e.message}")
            "LLM responded but couldn't parse the message"
        }
    }
}
