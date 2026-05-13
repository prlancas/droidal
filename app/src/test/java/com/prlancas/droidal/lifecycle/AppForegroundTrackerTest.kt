package com.prlancas.droidal.lifecycle

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for the process-wide foreground tracker that gates the LiteRT-LM
 * Gemma engine load against an active UI.
 *
 * The contract is load-bearing for app launch on the Samsung S23: if the
 * tracker reports `isForeground = false` while `MainActivity` is visible,
 * `ReflectorWorker` will load the LLM on the GPU/EGL context the activity
 * is rendering through, freeze the main thread, and break the input
 * dispatcher channel — which is exactly the ANR we've been chasing.
 */
class AppForegroundTrackerTest {

    @Before
    fun setUp() {
        // The tracker is a process-wide singleton so previous tests may
        // have driven it. Reset before every case.
        AppForegroundTracker.resetForTest()
    }

    @After
    fun tearDown() {
        AppForegroundTracker.resetForTest()
    }

    @Test
    fun `isForeground starts false before any activity is started`() {
        assertFalse(
            "Fresh tracker (no activities) must report background",
            AppForegroundTracker.isForeground(),
        )
    }

    @Test
    fun `isForeground becomes true after onActivityStarted`() {
        AppForegroundTracker.notifyStartedForTest()
        assertTrue(
            "After onActivityStarted, tracker must report foreground",
            AppForegroundTracker.isForeground(),
        )
    }

    @Test
    fun `isForeground returns false after balanced start_stop pair`() {
        AppForegroundTracker.notifyStartedForTest()
        AppForegroundTracker.notifyStoppedForTest()
        assertFalse(
            "After matching onActivityStopped, tracker must report background",
            AppForegroundTracker.isForeground(),
        )
    }

    @Test
    fun `isForeground stays true while transitioning between activities`() {
        // Real-world case: SettingsActivity is started before MainActivity
        // is stopped (Android's standard A->B handoff overlaps the two
        // started states for a moment). The tracker must not flicker to
        // background during that overlap, otherwise a worker dispatched
        // mid-transition could load LiteRT-LM exactly when both activities
        // are competing for the GPU.
        AppForegroundTracker.notifyStartedForTest() // MainActivity onStart
        AppForegroundTracker.notifyStartedForTest() // SettingsActivity onStart
        AppForegroundTracker.notifyStoppedForTest() // MainActivity onStop
        assertTrue(
            "Foreground must remain true during A->B activity handoff",
            AppForegroundTracker.isForeground(),
        )
        AppForegroundTracker.notifyStoppedForTest() // SettingsActivity onStop
        assertFalse(
            "After both activities stop, tracker must report background",
            AppForegroundTracker.isForeground(),
        )
    }

    @Test
    fun `unmatched onActivityStopped clamps counter at zero`() {
        // Defensive: a misbehaving harness (or an Android edge case where
        // onStop fires for an activity we never saw onStart for) must not
        // drive the counter negative, otherwise `isForeground` would
        // permanently report `false` when a later onStart brings it back
        // to zero, stranding the workers in retry-loops forever.
        AppForegroundTracker.notifyStoppedForTest()
        AppForegroundTracker.notifyStoppedForTest()
        AppForegroundTracker.notifyStoppedForTest()
        assertFalse(AppForegroundTracker.isForeground())

        AppForegroundTracker.notifyStartedForTest()
        assertTrue(
            "Counter must clamp at 0 so a real onStart still flips us foreground",
            AppForegroundTracker.isForeground(),
        )
    }

    @Test
    fun `concurrent start_stop callbacks balance out to zero`() {
        // The Android lifecycle callbacks are dispatched on the main
        // thread in practice, but the counter is shared with worker
        // threads that read `isForeground()` from the WorkManager pool.
        // Each Activity's onStart is always paired with a later onStop
        // for the same Activity — onStop never precedes its matching
        // onStart — so we model that here: every thread does its own
        // start, then yields, then its own stop. With that invariant
        // upheld, an atomic counter must end at exactly 0 even under
        // heavy contention, regardless of the unmatched-stop clamp.
        val threadCount = 64
        val barrier = CountDownLatch(1)
        val finished = CountDownLatch(threadCount)

        repeat(threadCount) {
            Thread {
                barrier.await()
                AppForegroundTracker.notifyStartedForTest()
                Thread.yield()
                AppForegroundTracker.notifyStoppedForTest()
                finished.countDown()
            }.start()
        }
        barrier.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertFalse(
            "Equal starts and stops must end in background state",
            AppForegroundTracker.isForeground(),
        )
    }

    @Test
    fun `concurrent isForeground reads see consistent state during start`() {
        // The hazard: a worker may call `isForeground()` from its own
        // dispatcher thread at the exact moment MainActivity#onStart is
        // running on the main thread. Reads must observe a true result
        // once the increment has happened — verified by counting how
        // many of N concurrent reads (after the start) see foreground.
        val readerCount = 32
        val barrier = CountDownLatch(1)
        val finished = CountDownLatch(readerCount)
        val sawForeground = AtomicInteger(0)

        AppForegroundTracker.notifyStartedForTest()

        repeat(readerCount) {
            Thread {
                barrier.await()
                if (AppForegroundTracker.isForeground()) sawForeground.incrementAndGet()
                finished.countDown()
            }.start()
        }
        barrier.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        // AtomicInteger publishes increments to all readers on get(); every
        // reader after the start must see foreground. If even one reader
        // sees `false`, workers could leak through and load Gemma during
        // launch.
        assertTrue(
            "All $readerCount concurrent readers must see foreground=true after onStart",
            sawForeground.get() == readerCount,
        )
    }
}
