@file:OptIn(DetachedPromptExecutorAPI::class)

package com.prlancas.droidal.brain
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.context.DetachedPromptExecutorAPI
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.AIAgentBuilderDslMarker
import ai.koog.agents.core.dsl.builder.AIAgentNodeDelegate
import ai.koog.agents.core.dsl.builder.AIAgentSubgraphBuilderBase
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.nodeLLMCompressHistory
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import ai.koog.prompt.message.Message
import android.util.Log
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.SuspendLatch
import com.prlancas.droidal.event.events.Say
import com.prlancas.droidal.event.events.SendToLLM
import com.prlancas.droidal.listen.Listen
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
object Agent {
    private val scope = MainScope()

    val toolRegistry =
        ToolRegistry {
            tools( toolSet = Tools())
        }

    init {
        scope.launch(newFixedThreadPoolContext(10,"LLMThreads")) {
            EventBus.subscribe<SendToLLM> { event ->
                runBlocking {
                    haveConversation(event.message)
                }
            }
        }
    }

    suspend fun haveConversation(message: String): String {
        try {
            val agent = AIAgent(
//                promptExecutor = simpleOllamaAIExecutor(
//                    baseUrl = "http://192.168.1.130:11434"
//                ),
                //llmModel = OllamaModels.Groq.LLAMA_3_GROK_TOOL_USE_8B,

//                promptExecutor = simpleOpenAIExecutor(Config.key("openapi_key")),
//                llmModel = OpenAIModels.Reasoning.O3Mini,

                strategy = chatStrategy("Droidal Agent", toolRegistry),

                promptExecutor = simpleGoogleAIExecutor(Config.key("gemini_key")),
                llmModel = GoogleModels.Gemini2_0Flash,

                systemPrompt = "You are a robot. All messages come from STT and your replies are spoken to the user using TTS. You can move around with the move tool but can also chat and answer any questions the user has",
                toolRegistry = toolRegistry,
                maxIterations = 10
            ){
                handleEvents {
                    onToolCallStarting { ctx ->
                        Log.i("AGENT",
                            "Tool ${ctx.tool.name}, args ${
                                ctx.toolArgs.toString().replace('\n', ' ').take(100)
                            }..."
                        )
                    }
                }
            }
            val reply = agent.run(message)
            Log.i("AGENT", "Reply: $reply")
        } catch (e: Exception) {
            Log.e("LLM_HANDLER", "Error parsing LLM response: ${e.message}")
            "LLM responded but couldn't parse the message"
            EventBus.publishAsync(Say("LLM responded but couldn't parse the message: ${e.message}"))
        }
        return ""
    }
}

private suspend fun speakSuspend(message: String): String {
    val suspendLatch = SuspendLatch(1)
    var reply: String? = null
    Log.i("LISTEN", "Calling Listen.listenAndReplySuspend with message $message")
    Listen.listenAndReplySuspend(message) { replyMsg ->
        Log.i("LISTEN", "onComplete callback was called with $replyMsg")
        reply = replyMsg
        suspendLatch.countDown()
    }
    Log.i("LISTEN", "Waiting on reply to $message")
    suspendLatch.await()
    Log.i("LISTEN", "Reply was received $reply")
    return reply ?: ""
}

fun chatStrategy(name: String, toolRegistry: ToolRegistry): AIAgentGraphStrategy<String, String> {
    return strategy(name) {
        val nodeSendInput by nodeLLMRequest()
        val nodeExecuteTool by nodeExecuteTool()
        val nodeSendToolResult by nodeLLMSendToolResult()
        val nodeCompressHistory by nodeLLMCompressHistory<ReceivedToolResult>()
        val nodeRequestMoreInput by nodeRequestMoreInput()

        // Define the flow of the agent
        edge(nodeStart forwardTo nodeSendInput)


        edge(
            (nodeSendInput forwardTo nodeRequestMoreInput)
                    onAssistantMessage { true }
        )

        edge(
            (nodeRequestMoreInput forwardTo nodeExecuteTool)
                    onToolCall { true }
        )

        edge(
            (nodeRequestMoreInput forwardTo nodeRequestMoreInput)
                    onAssistantMessage { true }
        )


        // If the LLM calls a tool, execute it
        edge(
            (nodeSendInput forwardTo nodeExecuteTool)
                    onToolCall { true }
        )

//        // If the history gets too large, compress it
//        edge(
//            (nodeExecuteTool forwardTo nodeCompressHistory)
//                    onCondition { _ -> llm.readSession { prompt.messages.size > 100 } }
//        )
//
//        edge(nodeCompressHistory forwardTo nodeSendToolResult)
//
//        // Otherwise, send the tool result directly
//        edge(
//            (nodeExecuteTool forwardTo nodeSendToolResult)
//                    onCondition { _ -> llm.readSession { prompt.messages.size <= 100 } }
//        )

        edge(
            (nodeExecuteTool forwardTo nodeSendToolResult)
        )

        // If the LLM calls another tool, execute it
        edge(
            (nodeSendToolResult forwardTo nodeExecuteTool)
                    onToolCall { true }
        )

        // If the LLM responds with a message, get user input
        edge(
            (nodeSendToolResult forwardTo nodeFinish)
                    onAssistantMessage { true }
        )
    }
}

@AIAgentBuilderDslMarker
fun AIAgentSubgraphBuilderBase<*, *>.nodeRequestMoreInput(
    name: String? = null
): AIAgentNodeDelegate<String, Message.Response> =
    node(name) { result ->
        Log.i("LLM", "Requesting more input from user after LLM response: $result")
        val reply = speakSuspend(result)
        llm.writeSession {
            updatePrompt {
                user(reply)
            }
            Log.i("LLM", "Added $reply and called the LLM again")
            requestLLM()
        }
    }