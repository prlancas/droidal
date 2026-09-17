package com.prlancas.droidal.event.events

/**
 * Published when an LLM performs a tool call. The main UI overlay listens
 * for this to show a transient debug notification (if enabled in settings).
 */
data class DebugToolCall(val name: String, val args: String)
