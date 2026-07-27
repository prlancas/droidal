package com.prlancas.droidal.brain.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * OpenAI / OpenRouter-format tool schema for [DroidalTools].
 *
 * Shape matches the OpenAI Chat Completions `tools[]` envelope, which
 * OpenRouter proxies transparently:
 * ```
 * [{"type": "function", "function": {"name": ..., "parameters": {...}}}]
 * ```
 *
 * Invocation flows through the shared [DroidalToolDispatcher].
 */
object OpenAIToolSchema {

    fun toolsJson(): JsonArray = JsonArray().apply {
        add(
            fn(
                name = "setName",
                description = "Record the user's name. Associates the currently detected face with this name and creates a per-user learning store.",
                properties = mapOf(
                    "name" to param("string", "The user's name."),
                ),
                required = listOf("name"),
            ),
        )
        add(
            fn(
                name = "addMemory",
                description = "Add a new entry to Droidal's persistent memory. target='user' for facts about the person; target='memory' for environment/robot facts.",
                properties = mapOf(
                    "target" to enumParam("Which file to modify.", listOf("memory", "user")),
                    "content" to param("string", "The new entry content."),
                ),
                required = listOf("target", "content"),
            ),
        )
        add(
            fn(
                name = "replaceMemory",
                description = "Replace an existing memory entry. oldText must be a unique substring of the entry being replaced.",
                properties = mapOf(
                    "target" to enumParam("Which file to modify.", listOf("memory", "user")),
                    "oldText" to param("string", "Unique substring of the entry to replace."),
                    "newContent" to param("string", "Replacement entry."),
                ),
                required = listOf("target", "oldText", "newContent"),
            ),
        )
        add(
            fn(
                name = "removeMemory",
                description = "Delete a memory entry. oldText must be a unique substring of the entry being removed.",
                properties = mapOf(
                    "target" to enumParam("Which file to modify.", listOf("memory", "user")),
                    "oldText" to param("string", "Unique substring of the entry to remove."),
                ),
                required = listOf("target", "oldText"),
            ),
        )
        add(
            fn(
                name = "skillsList",
                description = "List the agent-managed skills currently learned for this user.",
                properties = emptyMap(),
                required = emptyList(),
            ),
        )
        add(
            fn(
                name = "skillView",
                description = "Read the full SKILL.md text (or a supporting file) of a previously-learned skill.",
                properties = mapOf(
                    "name" to param("string", "Skill slug."),
                    "path" to param("string", "Optional sub-path inside the skill dir."),
                ),
                required = listOf("name"),
            ),
        )
        add(
            fn(
                name = "createSkill",
                description = "Save a brand-new skill (procedural how-to) to Droidal's library for this user.",
                properties = mapOf(
                    "name" to param("string", "Skill slug (kebab-case)."),
                    "content" to param("string", "Full SKILL.md markdown body."),
                ),
                required = listOf("name", "content"),
            ),
        )
        add(
            fn(
                name = "editSkill",
                description = "Replace the entire SKILL.md body of an existing skill. Prefer patchSkill for small edits.",
                properties = mapOf(
                    "name" to param("string", "Skill slug."),
                    "content" to param("string", "New full SKILL.md body."),
                ),
                required = listOf("name", "content"),
            ),
        )
        add(
            fn(
                name = "patchSkill",
                description = "Make a small targeted edit to a skill's SKILL.md by replacing a unique substring.",
                properties = mapOf(
                    "name" to param("string", "Skill slug."),
                    "oldString" to param("string", "Unique substring already present in the skill body."),
                    "newString" to param("string", "Replacement string."),
                ),
                required = listOf("name", "oldString", "newString"),
            ),
        )
        add(
            fn(
                name = "deleteSkill",
                description = "Delete a previously-saved skill.",
                properties = mapOf(
                    "name" to param("string", "Skill slug."),
                ),
                required = listOf("name"),
            ),
        )
        add(
            fn(
                name = "searchMemory",
                description = "Full-text search past conversations with this user. Returns recent matches first.",
                properties = mapOf(
                    "query" to param("string", "Free-text query."),
                    "max" to param("integer", "Maximum results (default 8)."),
                ),
                required = listOf("query"),
            ),
        )
        add(
            fn(
                name = "webSearch",
                description = "Search the web (DuckDuckGo) for current information.",
                properties = mapOf(
                    "query" to param("string", "Free-text web query."),
                    "max" to param("integer", "Maximum results (default 5)."),
                ),
                required = listOf("query"),
            ),
        )
        add(
            fn(
                name = "move",
                description = "Drive Droidal's body to an X,Y location.",
                properties = mapOf(
                    "x" to param("integer", "Horizontal offset in centimetres (negative = left)."),
                    "y" to param("integer", "Forward offset in centimetres (negative = backward)."),
                ),
                required = listOf("x", "y"),
            ),
        )
        add(
            fn(
                name = "exploreMode",
                description = "Turn autonomous exploration on or off. state='on' makes Droidal drive itself around to map and explore; state='off' stops it. Use freeze for an emergency stop.",
                properties = mapOf(
                    "state" to enumParam("Either 'on' to start exploring or 'off' to stop.", listOf("on", "off")),
                ),
                required = listOf("state"),
            ),
        )
        add(
            fn(
                name = "freeze",
                description = "Immediately stop and freeze all robot movement. Use if something is going wrong or the user says stop / freeze / halt / wait. Cancels navigation and stops exploring.",
                properties = emptyMap(),
                required = emptyList(),
            ),
        )
        add(
            fn(
                name = "endConversation",
                description = "End the current conversation cleanly. Call this when the user says goodbye or the conversation has reached a natural close. Still include a short farewell in the reply so Droidal speaks it.",
                properties = mapOf(
                    "reason" to param("string", "Optional short reason for ending (logged only)."),
                ),
                required = emptyList(),
            ),
        )
    }

    private fun fn(
        name: String,
        description: String,
        properties: Map<String, JsonObject>,
        required: List<String>,
    ): JsonObject = JsonObject().apply {
        addProperty("type", "function")
        add(
            "function",
            JsonObject().apply {
                addProperty("name", name)
                addProperty("description", description)
                add(
                    "parameters",
                    JsonObject().apply {
                        addProperty("type", "object")
                        add(
                            "properties",
                            JsonObject().also { obj ->
                                properties.forEach { (k, v) -> obj.add(k, v) }
                            },
                        )
                        add("required", JsonArray().apply { required.forEach { add(it) } })
                    },
                )
            },
        )
    }

    private fun param(type: String, description: String): JsonObject = JsonObject().apply {
        addProperty("type", type)
        addProperty("description", description)
    }

    private fun enumParam(description: String, values: List<String>): JsonObject = JsonObject().apply {
        addProperty("type", "string")
        addProperty("description", description)
        add("enum", JsonArray().apply { values.forEach { add(it) } })
    }
}
