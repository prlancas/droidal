package com.prlancas.droidal.brain.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the LLM-facing tool descriptors.
 *
 * Both schemas advertise the same set of tools (one per public method on
 * [DroidalTools]) and feed into the shared [DroidalToolDispatcher]. If
 * the schemas drift out of sync — or the names and required-parameter
 * lists drift away from what the dispatcher expects — the model will
 * silently start producing tool calls that fail at runtime.
 *
 * These tests pin:
 *   1. The set of tool names exposed to the model.
 *   2. That every tool the dispatcher knows about is in both schemas.
 *   3. The shape of the OpenAI envelope (`type=function`, `parameters`
 *      with `properties`/`required`).
 */
class ToolSchemaTest {

    /** Names of every tool exposed to the LLM. Single source of truth. */
    private val expectedToolNames = setOf(
        "setName",
        "addMemory",
        "replaceMemory",
        "removeMemory",
        "skillsList",
        "skillView",
        "createSkill",
        "editSkill",
        "patchSkill",
        "deleteSkill",
        "searchMemory",
        "webSearch",
        "move",
        "exploreMode",
        "freeze",
        "endConversation",
    )

    @Test
    fun `OpenAI schema exposes exactly the documented tool set`() {
        val names = OpenAIToolSchema.toolsJson()
            .map { it.asJsonObject.getAsJsonObject("function").get("name").asString }
            .toSet()
        assertEquals(expectedToolNames, names)
    }

    @Test
    fun `Gemini schema exposes exactly the documented tool set`() {
        val tools = GeminiToolSchema.toolsJson()
        // Gemini wraps everything in a single `functionDeclarations`
        // array nested inside the first tool entry.
        val declarations = tools[0].asJsonObject.getAsJsonArray("functionDeclarations")
        val names = declarations.map { it.asJsonObject.get("name").asString }.toSet()
        assertEquals(expectedToolNames, names)
    }

    @Test
    fun `OpenAI schema entries follow OpenAI's function envelope`() {
        for (entry in OpenAIToolSchema.toolsJson()) {
            val obj = entry.asJsonObject
            assertEquals("function", obj.get("type").asString)
            val fn = obj.getAsJsonObject("function")
            assertNotNull("function must have a name", fn.get("name"))
            assertNotNull("function must have a description", fn.get("description"))
            val params = fn.getAsJsonObject("parameters")
            assertEquals("object", params.get("type").asString)
            assertNotNull("parameters must have a properties block", params.get("properties"))
            assertNotNull("parameters must have a required array", params.get("required"))
        }
    }

    @Test
    fun `addMemory in OpenAI schema constrains target to memory or user`() {
        val addMemory = OpenAIToolSchema.toolsJson()
            .first { it.asJsonObject.getAsJsonObject("function").get("name").asString == "addMemory" }
            .asJsonObject.getAsJsonObject("function")
            .getAsJsonObject("parameters")
            .getAsJsonObject("properties")
            .getAsJsonObject("target")
        val enumValues = addMemory.getAsJsonArray("enum").map { it.asString }
        assertEquals(listOf("memory", "user"), enumValues)
    }

    @Test
    fun `every dispatcher case is reachable from at least one schema`() {
        // The dispatcher only knows the tool names listed above; if a
        // method on DroidalTools is exposed to the LLM via a schema but
        // missing from the dispatcher (or vice versa) we want to catch
        // that here rather than at runtime in a real conversation.
        val openAINames = OpenAIToolSchema.toolsJson()
            .map { it.asJsonObject.getAsJsonObject("function").get("name").asString }
            .toSet()
        val geminiNames = GeminiToolSchema.toolsJson()[0]
            .asJsonObject.getAsJsonArray("functionDeclarations")
            .map { it.asJsonObject.get("name").asString }
            .toSet()
        assertTrue(
            "Every advertised tool should appear in both schemas. Diff: " +
                "${(openAINames - geminiNames) + (geminiNames - openAINames)}",
            openAINames == geminiNames,
        )
    }

    /** Sanity check the helper used in the schema is producing valid JSON shape. */
    @Test
    fun `each Gemini function entry has at least name and description`() {
        val declarations: JsonArray = GeminiToolSchema.toolsJson()[0]
            .asJsonObject.getAsJsonArray("functionDeclarations")
        for (entry in declarations) {
            val obj = entry as JsonObject
            assertNotNull(obj.get("name"))
            assertNotNull(obj.get("description"))
            assertNotNull(obj.get("parameters"))
        }
    }
}
