package com.prlancas.droidal.brain

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.prlancas.droidal.face.FaceRecognitionManager
import com.prlancas.droidal.memory.Memory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class Tools : ToolSet{

    private val toolsScope = CoroutineScope(Dispatchers.Default)

    @Tool
    @LLMDescription("Move to a new location")
    fun move(x: Int, y: Int): String {
        return "Moving"
    }

    @Tool
    @LLMDescription("Set the users name and retrieve their interests. This will also associate the current detected face with this name for future recognition.")
    fun setName(name: String): String {
        // Associate the current face with this user name
        toolsScope.launch {
            FaceRecognitionManager.associateCurrentFaceWithUser(name)
        }
        return Memory.getInterestsAsString(name)
    }

    @Tool
    @LLMDescription("Add an interest for the user")
    fun addInterest(name: String, interest: String): String {
        Memory.addInterest(name, interest)
        return "remembered"
    }

}