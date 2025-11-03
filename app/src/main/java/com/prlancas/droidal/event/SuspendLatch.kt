package com.prlancas.droidal.event

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * A suspend-compatible alternative to CountDownLatch that works with coroutines.
 * This prevents deadlocks when used in coroutine contexts.
 */
class SuspendLatch(private val count: Int = 1) {
    private var remaining = count
    private val waiters = mutableListOf<kotlin.coroutines.Continuation<Unit>>()
    private val lock = Any()

    /**
     * Suspends until the latch count reaches zero.
     * This is the suspend-compatible equivalent of CountDownLatch.await()
     */
    suspend fun await() {
        if (remaining <= 0) return

        suspendCancellableCoroutine<Unit> { continuation ->
            synchronized(lock) {
                if (remaining <= 0) {
                    continuation.resume(Unit)
                } else {
                    waiters.add(continuation)
                }
            }
        }
    }

    /**
     * Decrements the count and resumes all waiting coroutines if count reaches zero.
     * This is the suspend-compatible equivalent of CountDownLatch.countDown()
     */
    fun countDown() {
        synchronized(lock) {
            if (remaining > 0) {
                remaining--
                if (remaining == 0) {
                    // Resume all waiting coroutines
                    waiters.forEach { continuation ->
                        try {
                            continuation.resume(Unit)
                        } catch (e: Exception) {
                            // Continuation might be cancelled, ignore
                        }
                    }
                    waiters.clear()
                }
            }
        }
    }

    /**
     * Gets the current count of remaining operations.
     */
    fun getCount(): Int = synchronized(lock) { remaining }

    /**
     * Checks if the latch has been released (count is zero).
     */
    fun isReleased(): Boolean = synchronized(lock) { remaining == 0 }
}
