package com.prlancas.droidal.brain
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import android.util.Log
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import com.prlancas.droidal.event.events.SendToLLM
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

@OptIn(DelicateCoroutinesApi::class)
object Agent {
    private val scope = MainScope()

    @Serializable
    data class SpeakArgs(val message: String)

    @Serializable
    data class MoveToArgs(val location: String)

    val toolRegistry =
        ToolRegistry {
            tools( toolSet = Tools())
        }

    init {
        scope.launch(newSingleThreadContext("LLMThread")) {
            EventBus.subscribe<SendToLLM> { event ->
                runBlocking {
                    sendMessage(event.message)
                }
            }
        }
    }

    suspend fun sendMessage(message: String): String {
        try {
            val agent = AIAgent(
//                promptExecutor = simpleOllamaAIExecutor(
//                    baseUrl = "http://192.168.1.130:11434"
//                ),
                //llmModel = OllamaModels.Groq.LLAMA_3_GROK_TOOL_USE_8B,

//                promptExecutor = simpleOpenAIExecutor(Config.key("openapi_key")),
//                llmModel = OpenAIModels.Reasoning.O3Mini,

                promptExecutor = simpleGoogleAIExecutor(Config.key("gemini_key")),
                llmModel = GoogleModels.Gemini2_0Flash,

                systemPrompt = "You are a robot that can talk to the user with the speak tool and move around with the move tool. Always speak your replies using the tool normal replies are ignored",
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
            val result = agent.run(message)
            EventBus.publishAsync(Say(result))
            return result
        } catch (e: Exception) {
            Log.e("LLM_HANDLER", "Error parsing LLM response: ${e.message}")
            "LLM responded but couldn't parse the message"
            EventBus.publishAsync(Say("LLM responded but couldn't parse the message: ${e.message}"))
        }
        return ""
    }
}