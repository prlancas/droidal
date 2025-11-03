package com.prlancas.droidal.memory

import com.prlancas.droidal.event.events.StartConversation

private const val basicDescription =
    """You are Droidal, an advanced AI assistant integrated into a Robot running as an Android application."""

object Memory {
    var interests: HashMap<String, List<String>> = HashMap()
    var lastConverstaionSummary: HashMap<String, Conversation> = HashMap()

    fun generateSystemPrompt(startConversation: StartConversation): String =
        if (startConversation.user != null) {
            """
            $basicDescription 
            Your primary role is to assist the user, $startConversation.user, with various tasks using natural language processing and understanding. The current time is ${java.time.LocalDateTime.now()}.
            
            ${getInterestsAsString(startConversation.user)}
            
            The last convesation you had with $startConversation.user can be summarised as: ${lastConversationSummary(startConversation.user)}
            
            When responding to $startConversation.user, always maintain a friendly and helpful tone. Use the information about $startConversation.user's interests to make your responses more personalized and engaging.
            
        """.trimIndent()
        } else {
            """
            $basicDescription 
            Your primary role is to assist users with various tasks using natural language processing and understanding. The current time is ${java.time.LocalDateTime.now()}.
            
            You do not recognise the user you are interacting with. Try to find out their name and call setName tool. Learn about their interests during the conversation.
        """.trimIndent()
        }

    private fun lastConversationSummary(user: String): String {
        return lastConverstaionSummary.getOrDefault(user, Conversation("No previous conversation recorded")).summary
    }

    fun getInterestsAsString(user: String): String =
        interests.getOrDefault(user, unknownInterests()).joinToString( prefix = "$user is interested in", separator =  ", ")

    private fun unknownInterests(): List<String> = listOf("There interests are not known yet try to determine these in this conversation")

    fun addInterest(user: String, newInterest: String) {
        val currentInterests = interests.getOrDefault(user, emptyList())
        val updatedInterests = (currentInterests + listOf(newInterest)).distinct()
        interests[user] = updatedInterests
    }

    fun storeConversation(user: String, summary: String) {
        lastConverstaionSummary[user] = Conversation(summary)
    }
}