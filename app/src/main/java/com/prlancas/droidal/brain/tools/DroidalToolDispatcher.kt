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
            "addInterest" -> tools.addInterest(
                name = args.stringArg("name"),
                interest = args.stringArg("interest"),
            )
            "move" -> tools.move(
                x = args.intArg("x"),
                y = args.intArg("y"),
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
}
