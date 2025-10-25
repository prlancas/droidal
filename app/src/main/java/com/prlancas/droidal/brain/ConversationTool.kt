//package com.prlancas.droidal.brain
//
//import ai.koog.agents.core.tools.SimpleTool
//import ai.koog.agents.core.tools.annotations.LLMDescription
//import kotlinx.serialization.KSerializer
//import kotlinx.serialization.Serializable
//
//public object ConversationTool : SimpleTool<ConversationTool.Args>() {
////
////    @Serializable
////    data class Args(
////        @property:LLMDescription("Message from the agent")
////        val message: String
////    )
////
////    override val argsSerializer: KSerializer<Args> = Args.serializer()
////    override val name: String = "say_to_user"
////    override val description: String = "Service tool, used by the agent to talk."
////
////    override suspend fun doExecute(args: Args): String {
////        println("Agent says: ${args.message}")
////        return "DONE"
////    }
//}