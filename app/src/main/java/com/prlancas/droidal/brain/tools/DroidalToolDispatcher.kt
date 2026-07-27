package com.prlancas.droidal.brain.tools

import android.util.Log
import com.google.gson.JsonObject

/**
 * Format-agnostic dispatcher from `(functionName, argsJson)` → actual
 * [DroidalTools] call.
 *
 * Shared by [GeminiToolSchema] (Gemini REST format) and
 * [OpenAIToolSchema] (OpenAI / OpenRouter format) — both pass the args as
 * a [JsonObject] and get back a `Map<String, Any>` that each provider then
 * serialises in its own tool-response envelope.
 *
 * Keeping this hand-written (rather than reflection-driven) means adding a
 * new tool is exactly: one method on [DroidalTools] + one `when` case here
 * + one entry in each schema's `toolsJson()`.
 */
object DroidalToolDispatcher {

    private const val TAG = "DroidalToolDispatcher"

    fun invoke(
        tools: DroidalTools,
        functionName: String,
        args: JsonObject,
    ): Map<String, Any> = try {
        when (functionName) {
            "setName" -> tools.setName(args.stringArg("name"))
            "addMemory" -> tools.addMemory(
                target = args.stringArg("target"),
                content = args.stringArg("content"),
            )
            "replaceMemory" -> tools.replaceMemory(
                target = args.stringArg("target"),
                oldText = args.stringArg("oldText"),
                newContent = args.stringArg("newContent"),
            )
            "removeMemory" -> tools.removeMemory(
                target = args.stringArg("target"),
                oldText = args.stringArg("oldText"),
            )
            "skillsList" -> tools.skillsList()
            "skillView" -> tools.skillView(
                name = args.stringArg("name"),
                path = args.optString("path").orEmpty(),
            )
            "createSkill" -> tools.createSkill(
                name = args.stringArg("name"),
                content = args.stringArg("content"),
            )
            "editSkill" -> tools.editSkill(
                name = args.stringArg("name"),
                content = args.stringArg("content"),
            )
            "patchSkill" -> tools.patchSkill(
                name = args.stringArg("name"),
                oldString = args.stringArg("oldString"),
                newString = args.stringArg("newString"),
            )
            "deleteSkill" -> tools.deleteSkill(
                name = args.stringArg("name"),
            )
            "searchMemory" -> tools.searchMemory(
                query = args.stringArg("query"),
                max = args.optInt("max") ?: 8,
            )
            "webSearch" -> tools.webSearch(
                query = args.stringArg("query"),
                max = args.optInt("max") ?: 5,
            )
            "move" -> tools.move(
                x = args.intArg("x"),
                y = args.intArg("y"),
            )
            "exploreMode" -> tools.exploreMode(
                state = args.stringArg("state"),
            )
            "freeze" -> tools.freeze()
            "endConversation" -> tools.endConversation(
                reason = args.optString("reason").orEmpty(),
            )
            else -> {
                Log.w(TAG, "Unknown tool requested by model: $functionName")
                mapOf("error" to "Unknown tool: $functionName")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Tool '$functionName' failed: ${e.message}", e)
        mapOf("error" to (e.message ?: "invocation failed"))
    }

    private fun JsonObject.stringArg(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString
            ?: throw IllegalArgumentException("Missing string argument '$key'")

    private fun JsonObject.intArg(key: String): Int =
        get(key)?.takeIf { !it.isJsonNull }?.asInt
            ?: throw IllegalArgumentException("Missing int argument '$key'")

    private fun JsonObject.optString(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString

    private fun JsonObject.optInt(key: String): Int? =
        get(key)?.takeIf { !it.isJsonNull }?.asInt
}
