package com.prlancas.droidal.brain

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.prlancas.droidal.listen.Listen
import com.prlancas.droidal.event.SuspendLatch
import kotlinx.coroutines.runBlocking

class Tools : ToolSet{
    @Tool
    @LLMDescription("Send a message to the user and get a reply")
    fun speak(message: String): String {
        return runBlocking {
            speakSuspend(message)
        }
    }

    /**
     * Suspend version of speak that uses SuspendLatch to avoid deadlocks.
     */
    private suspend fun speakSuspend(message: String): String {
        val suspendLatch = SuspendLatch(1)
        var reply: String? = null
        Listen.listenAndReplySuspend(message) { replyMsg ->
            reply = replyMsg
            suspendLatch.countDown()
        }
        suspendLatch.await()
        return reply ?: ""
    }

    @Tool
    @LLMDescription("Move to a new location")
    fun move(x: Int, y: Int): String {
        return "Done"
    }
}