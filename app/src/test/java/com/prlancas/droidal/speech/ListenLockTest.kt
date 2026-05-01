package com.prlancas.droidal.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [ListenLock] — the "no double-listen" guard around
 * [SpeechToText.startListening].
 *
 * Two concurrent calls into `startListening` (e.g. the agent's listen-
 * with-retry loop racing a stray wake-word callback) would otherwise
 * each create their own SpeechRecognizer and fight over the
 * microphone. The lock makes the second caller bail cleanly with a
 * `null` reply so the first session can complete.
 */
class ListenLockTest {

    @Test
    fun `tryStart claims the slot exactly once`() {
        val lock = ListenLock()
        assertTrue("First caller must win", lock.tryStart())
        assertFalse("Second caller must be rejected", lock.tryStart())
        assertTrue(lock.isListening())
    }

    @Test
    fun `release frees the slot for the next caller`() {
        val lock = ListenLock()
        lock.tryStart()
        lock.release()
        assertFalse(lock.isListening())
        assertTrue("After release the next start should succeed", lock.tryStart())
    }

    @Test
    fun `release without a prior start is a no-op`() {
        val lock = ListenLock()
        lock.release()
        assertFalse(lock.isListening())
        assertTrue(lock.tryStart())
    }

    @Test
    fun `concurrent tryStart calls elect exactly one winner`() {
        // The real-world hazard: agent retry loop and a wake-word
        // callback both call startListening at roughly the same instant.
        // Exactly one must claim the mic; the rest must back off.
        val threadCount = 64
        val lock = ListenLock()
        val winners = AtomicInteger(0)
        val barrier = CountDownLatch(1)
        val done = CountDownLatch(threadCount)

        repeat(threadCount) {
            Thread {
                barrier.await()
                if (lock.tryStart()) winners.incrementAndGet()
                done.countDown()
            }.start()
        }

        barrier.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(
            "Only one of $threadCount concurrent callers may win the lock",
            1,
            winners.get(),
        )
    }

    @Test
    fun `start - release - start cycle works repeatedly`() {
        val lock = ListenLock()
        repeat(50) {
            assertTrue("cycle $it should claim", lock.tryStart())
            lock.release()
        }
    }
}
