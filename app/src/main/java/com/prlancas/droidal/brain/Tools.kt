package com.prlancas.droidal.brain

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.prlancas.droidal.listen.Listen
import java.util.concurrent.CountDownLatch

class Tools : ToolSet{
    @Tool
    @LLMDescription("Send a message to the user and get a reply")
    fun speak(message: String): String {
        val countDownLatch = CountDownLatch(1)
        var reply: String? = null
        Listen.listenAndReply(message) { replyMsg ->
            reply = replyMsg
            countDownLatch.countDown()
        }
        countDownLatch.await()
        return reply ?: ""
    }

    @Tool
    @LLMDescription("Move to a new location")
    fun move(x: Int, y: Int): String {
        return "Done"
    }
}