package com.prlancas.droidal.brain

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet

class Tools : ToolSet{

    @Tool
    @LLMDescription("Move to a new location")
    fun move(x: Int, y: Int): String {
        return "Done"
    }
}