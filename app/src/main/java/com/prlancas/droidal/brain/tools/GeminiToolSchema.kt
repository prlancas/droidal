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
                    description = "Record the user's name. Associates the currently detected face with this name and creates a per-user learning store. Returns what Droidal already knows about them.",
                    properties = mapOf(
                        "name" to param("string", "The user's name."),
                    ),
                    required = listOf("name"),
                ),
            )
            add(
                fn(
                    name = "addMemory",
                    description = "Add a new entry to Droidal's persistent memory. target='user' for facts about the person; target='memory' for environment/robot facts. One sentence per entry is best.",
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
                    description = "Replace an existing memory entry with a new one. oldText must be a unique substring of the entry being replaced.",
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
                    description = "Make a small targeted edit to a skill's SKILL.md by replacing a unique substring. Preferred for fine-tuning.",
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
                    description = "Delete a previously-saved skill. Use only when the skill turned out to be wrong or obsolete.",
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
                    description = "Search the web (DuckDuckGo) for current information. Use when the user asks about news, recent events, or facts that may have changed since training.",
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
                    description = "Drive Droidal's body to an X,Y location. Coordinates are in centimetres relative to its current position.",
                    properties = mapOf(
                        "x" to param("integer", "Horizontal offset in centimetres (negative = left)."),
                        "y" to param("integer", "Forward offset in centimetres (negative = backward)."),
                    ),
                    required = listOf("x", "y"),
                ),
            )
            add(
                fn(
                    name = "endConversation",
                    description = "End the current conversation cleanly. Call this when the user has clearly said goodbye / wants to stop talking, OR when the conversation has otherwise reached a natural close. Still produce a short farewell sentence in the same reply so Droidal speaks it before the loop exits.",
                    properties = mapOf(
                        "reason" to param("string", "Optional short reason for ending (logged only, never spoken)."),
                    ),
                    required = emptyList(),
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

    private fun enumParam(description: String, values: List<String>): JsonObject = JsonObject().apply {
        addProperty("type", "string")
        addProperty("description", description)
        add("enum", JsonArray().apply { values.forEach { add(it) } })
    }
}
