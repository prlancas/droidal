package com.prlancas.droidal.event.events

enum class Expression {
    NORMAL,
    SLEEP,
    BLINK,
    THINKING,
    SLEEPY,
    CUTE,
    BLOODSHOT
}

data class Look(val x: Float, val y: Float, val expression: Expression = Expression.NORMAL)
