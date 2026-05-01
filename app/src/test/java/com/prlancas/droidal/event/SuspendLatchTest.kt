package com.prlancas.droidal.event

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SuspendLatch] — a coroutine-friendly CountDownLatch.
 *
 * The agent + `Speak` queue several suspend coroutines that block on
 * shared latches; if those latches were `CountDownLatch.await()` they'd
 * deadlock the dispatcher, so getting the suspend semantics right is
 * load-bearing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SuspendLatchTest {

    @Test
    fun `count of zero releases immediately`() = runTest {
        val latch = SuspendLatch(count = 0)
        assertTrue(latch.isReleased())
        latch.await() // must not suspend
    }

    @Test
    fun `await suspends until countDown reaches zero`() = runTest {
        val latch = SuspendLatch(count = 2)
        var resumed = false

        val waiter = launch {
            latch.await()
            resumed = true
        }

        // Run the launched waiter up to its first suspension point.
        testScheduler.runCurrent()
        assertFalse("Should still be blocked after first run", resumed)

        latch.countDown()
        testScheduler.runCurrent()
        assertFalse("One more countDown still required", resumed)

        latch.countDown()
        testScheduler.runCurrent()
        assertTrue("Latch released, waiter should have resumed", resumed)

        waiter.join()
        assertTrue(latch.isReleased())
        assertEquals(0, latch.getCount())
    }

    @Test
    fun `multiple waiters all resume on release`() = runTest {
        val latch = SuspendLatch(count = 1)
        val a = async { latch.await(); 1 }
        val b = async { latch.await(); 2 }
        val c = async { latch.await(); 3 }

        testScheduler.runCurrent()
        assertFalse("waiters should still be suspended", a.isCompleted)

        latch.countDown()
        testScheduler.runCurrent()

        assertEquals(1, a.await())
        assertEquals(2, b.await())
        assertEquals(3, c.await())
    }

    @Test
    fun `extra countDown calls past zero are no-op`() = runTest {
        val latch = SuspendLatch(count = 1)
        latch.countDown()
        latch.countDown()
        latch.countDown()
        assertEquals(0, latch.getCount())
        assertTrue(latch.isReleased())
        latch.await()
    }
}
