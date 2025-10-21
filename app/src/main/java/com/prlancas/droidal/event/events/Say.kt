package com.prlancas.droidal.event.events

data class Say(val sentence: String, val onComplete: (() -> Unit)? = null)
