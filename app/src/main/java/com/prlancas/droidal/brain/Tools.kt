package com.prlancas.droidal.brain

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.prlancas.droidal.memory.Memory

class Tools : ToolSet{

    @Tool
    @LLMDescription("Move to a new location")
    fun move(x: Int, y: Int): String {
        return "Moving"
    }

    @Tool
    @LLMDescription("Set the users name and retrieve their interests")
    fun setName(name: String): String = Memory.getInterestsAsString(name)

    @Tool
    @LLMDescription("Add an interest for the user")
    fun addInterest(name: String, interest: String): String {
        Memory.addInterest(name, interest)
        return "remembered"
    }

}