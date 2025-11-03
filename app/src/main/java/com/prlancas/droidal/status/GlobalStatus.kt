package com.prlancas.droidal.status

object GlobalStatus {
    @Volatile
    var isListeningForWakeWord: Boolean = false
    @Volatile
    var isAwake: Boolean = false
}