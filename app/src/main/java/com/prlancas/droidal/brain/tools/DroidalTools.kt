package com.prlancas.droidal.brain.tools

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.prlancas.droidal.face.FaceRecognitionManager
import com.prlancas.droidal.memory.Memory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The single source of truth for tools the LLM can call.
 *
 * Uses LiteRT-LM's native `@Tool` / `@ToolParam` annotations (see
 * [gallery TinyGardenTools](https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/tinygarden/TinyGardenTools.kt)).
 * When chatting with a local LiteRT-LM model, this class is wrapped with
 * `com.google.ai.edge.litertlm.tool(...)` and passed to `ConversationConfig`,
 * which means LiteRT-LM handles the function-call loop natively — tool calls
 * become invisible to our chat code.
 *
 * For the cloud Gemini provider, the same methods are invoked from
 * [GeminiToolSchema] via an explicit dispatcher (no reflection).
 *
 * Every method must return `Map<String, Any>` — that is the shape LiteRT-LM
 * feeds back to the model as the tool's result.
 *
 * Robot-control stubs (`move`) are placeholders: swap the body for the
 * actual actuator call once the hardware bindings exist.
 */
class DroidalTools : ToolSet {

    private val scope = CoroutineScope(Dispatchers.Default)

    @Tool(description = "Record the user's name so Droidal can greet them by name in future and associates the currently detected face with this name. Returns what Droidal already knows about their interests.")
    fun setName(
        @ToolParam(description = "The user's name.") name: String,
    ): Map<String, Any> {
        Log.i(TAG, "setName($name)")
        scope.launch { FaceRecognitionManager.associateCurrentFaceWithUser(name) }
        return mapOf(
            "result" to "success",
            "name" to name,
            "knownInterests" to Memory.getInterestsAsString(name),
        )
    }

    @Tool(description = "Remember that a named user is interested in a topic so Droidal can bring it up in future conversations.")
    fun addInterest(
        @ToolParam(description = "The user whose interest is being recorded.") name: String,
        @ToolParam(description = "The topic, hobby, or thing the user is interested in.") interest: String,
    ): Map<String, Any> {
        Log.i(TAG, "addInterest($name, $interest)")
        Memory.addInterest(name, interest)
        return mapOf("result" to "success", "name" to name, "interest" to interest)
    }

    @Tool(description = "Drive Droidal's body to an X,Y location. Coordinates are in centimetres relative to its current position. Positive X moves right, positive Y moves forward.")
    fun move(
        @ToolParam(description = "Horizontal offset in centimetres (negative = left).") x: Int,
        @ToolParam(description = "Forward offset in centimetres (negative = backward).") y: Int,
    ): Map<String, Any> {
        Log.i(TAG, "move(x=$x, y=$y)")
        // Robot-control hook: replace this body with the real actuator call
        // once the hardware SDK is wired in. Returning "queued" rather than
        // "success" keeps the model aware the motion is asynchronous.
        return mapOf("result" to "queued", "x" to x, "y" to y)
    }

    companion object {
        private const val TAG = "DroidalTools"
    }
}
