package com.prlancas.droidal.event.events

data class StartConversation(val startedByUser: Boolean, val message: String, val user: String? = null)