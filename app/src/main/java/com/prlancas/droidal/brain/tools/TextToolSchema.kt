package com.prlancas.droidal.brain.tools

/**
 * Generates a human-readable text schema for [DroidalTools] by converting
 * the [GeminiToolSchema] declarations into a text block.
 *
 * Appended to system prompts for models that don't support native tool
 * calling (like Jimmy / OpenRouter fallback).
 */
object TextToolSchema {
    fun generate(): String {
        val sb = StringBuilder()
        sb.append("## Tool Use Instructions\n")
        sb.append(
            "You have access to the tools listed below. If you need to use a tool, " +
                "you must emit the call using this exact syntax:\n\n",
        )
        sb.append("`toolName(arg1=\"value\", arg2=123)`\n\n")
        sb.append("### Rules:\n")
        sb.append(
            "1. **Format**: Use `name(args)`. Strings must be in double quotes. " +
                "Numbers and booleans should not be quoted.\n",
        )
        sb.append("2. **Placement**: Put tool calls at the very beginning of your response, each on its own line.\n")
        sb.append("3. **Chain**: You can call multiple tools in one turn.\n")
        sb.append("4. **Thought**: You can provide your verbal response after the tool calls.\n")
        sb.append(
            "5. **No Duplicate Memories**: Do NOT use `addMemory` or `setName` to store facts " +
                "that were already provided to you in the system prompt or conversation history. " +
                "Only store new, important information.\n\n",
        )
        sb.append("## Available Tools\n")

        val toolsJson = GeminiToolSchema.toolsJson()
        val declarations = toolsJson[0].asJsonObject.getAsJsonArray("functionDeclarations")

        for (declElement in declarations) {
            val decl = declElement.asJsonObject
            val name = decl.get("name").asString
            val desc = decl.get("description").asString
            sb.append("- **$name**: $desc\n")

            val params = decl.getAsJsonObject("parameters")
            val props = params.getAsJsonObject("properties")
            if (props != null && props.size() > 0) {
                props.entrySet().forEach { (pName, pValue) ->
                    val pObj = pValue.asJsonObject
                    val pType = pObj.get("type").asString
                    val pDesc = pObj.get("description").asString
                    sb.append("  - `$pName` ($pType): $pDesc\n")
                }
            }
            sb.append("\n")
        }
        return sb.toString()
    }
}
