package com.prlancas.droidal.listen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for the wake-word double-trigger guard.
 *
 * Porcupine runs on its own audio thread and a single utterance of the
 * keyword can produce two callbacks back-to-back. Without this guard
 * Droidal would process both — saying "yes? yes?" and then later
 * "Pardon? Pardon?" while the mic is contended — so the contract here
 * is load-bearing for the wake-word UX.
 */
class WakeGuardTest {

    @Test
    fun `tryAwaken succeeds first time and fails the second`() {
        val guard = WakeGuard()
        assertTrue("First wake-word claim should succeed", guard.tryAwaken())
        assertFalse(
            "Second wake-word claim while still awake must be rejected",
            guard.tryAwaken(),
        )
        assertTrue(guard.isAwake())
    }

    @Test
    fun `release allows a fresh tryAwaken to succeed`() {
        val guard = WakeGuard()
        guard.tryAwaken()
        guard.release()
        assertFalse(guard.isAwake())
        assertTrue(
            "After release, the next wake-word callback should be honoured",
            guard.tryAwaken(),
        )
    }

    @Test
    fun `release is idempotent`() {
        val guard = WakeGuard()
        guard.tryAwaken()
        guard.release()
        guard.release() // second release must not throw or flip state.
        assertFalse(guard.isAwake())
    }

    @Test
    fun `concurrent wake-word callbacks elect exactly one winner`() {
        // Simulate the real-world hazard: Porcupine's audio thread fires
        // two awaken callbacks essentially simultaneously. Exactly one
        // should win the CAS and be allowed through; the other must be
        // dropped silently.
        val threadCount = 32
        val guard = WakeGuard()
        val winners = AtomicInteger(0)
        val barrier = CountDownLatch(1)
        val finished = CountDownLatch(threadCount)

        repeat(threadCount) {
            Thread {
                barrier.await()
                if (guard.tryAwaken()) winners.incrementAndGet()
                finished.countDown()
            }.start()
        }

        barrier.countDown() // Release all threads at once.
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(
            "Exactly one of $threadCount concurrent callbacks must win",
            winners.get() == 1,
        )
    }
}
