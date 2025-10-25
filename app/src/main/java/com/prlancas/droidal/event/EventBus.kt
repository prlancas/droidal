package com.prlancas.droidal.event

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

object EventBus {
    private val _events = MutableSharedFlow<Any>()
    val events = _events.asSharedFlow()

    /**
     * Publishes an event asynchronously without blocking the current thread.
     * This is the recommended alternative to blockPublish().
     */
    fun publishAsync(event: Any) {
        CoroutineScope(Dispatchers.Default).launch {
            publish(event)
        }
    }

    /**
     * Publishes an event. This is the preferred method for publishing events.
     * Use this in suspend functions or coroutine contexts.
     */
    suspend fun publish(event: Any) {
        _events.emit(event)
    }

    suspend inline fun <reified T> subscribe(crossinline onEvent: (T) -> Unit) {
        events.filterIsInstance<T>()
            .collectLatest { event ->
                coroutineContext.ensureActive()
                onEvent(event)
            }
    }
}