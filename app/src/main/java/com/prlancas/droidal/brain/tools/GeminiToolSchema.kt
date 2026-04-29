package com.prlancas.droidal.brain.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Gemini-format tool schema for [DroidalTools].
 *
 * Shape matches Gemini REST's `tools[].functionDeclarations[]` envelope.
 * Invocation flows through the shared [DroidalToolDispatcher].
 */
object GeminiToolSchema {

    fun toolsJson(): JsonArray {
        val declarations = JsonArray().apply {
            add(
                fn(
                    name = "setName",
                    description = "Record the user's name so Droidal can greet them by name in future and associates the currently detected face with this name. Returns what Droidal already knows about their interests.",
                    properties = mapOf(
                        "name" to param("string", "The user's name."),
                    ),
                    required = listOf("name"),
                ),
            )
            add(
                fn(
                    name = "addInterest",
                    description = "Remember that a named user is interested in a topic so Droidal can bring it up in future conversations.",
                    properties = mapOf(
                        "name" to param("string", "The user whose interest is being recorded."),
                        "interest" to param("string", "The topic, hobby, or thing the user is interested in."),
                    ),
                    required = listOf("name", "interest"),
                ),
            )
            add(
                fn(
                    name = "move",
                    description = "Drive Droidal's body to an X,Y location. Coordinates are in centimetres relative to its current position. Positive X moves right, positive Y moves forward.",
                    properties = mapOf(
                        "x" to param("integer", "Horizontal offset in centimetres (negative = left)."),
                        "y" to param("integer", "Forward offset in centimetres (negative = backward)."),
                    ),
                    required = listOf("x", "y"),
                ),
            )
        }
        return JsonArray().apply {
            add(JsonObject().apply { add("functionDeclarations", declarations) })
        }
    }

    private fun fn(
        name: String,
        description: String,
        properties: Map<String, JsonObject>,
        required: List<String>,
    ): JsonObject = JsonObject().apply {
        addProperty("name", name)
        addProperty("description", description)
        val params = JsonObject().apply {
            addProperty("type", "object")
            val props = JsonObject().also { obj ->
                properties.forEach { (k, v) -> obj.add(k, v) }
            }
            add("properties", props)
            add("required", JsonArray().apply { required.forEach { add(it) } })
        }
        add("parameters", params)
    }

    private fun param(type: String, description: String): JsonObject = JsonObject().apply {
        addProperty("type", type)
        addProperty("description", description)
    }
}
