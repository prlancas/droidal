package com.prlancas.droidal.brain.tools

import android.util.Log
import com.prlancas.droidal.brain.Agent
import com.prlancas.droidal.vision.ObjectLocalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Headless "map the room while you drive" loop.
 *
 * While autonomous exploration is on, this periodically runs the
 * [SpatialMemory] capture pipeline so the object map self-populates without the
 * user having to ask "what can you see". It's:
 *
 *  - **distance + time gated** — it only captures after the robot has moved
 *    [MIN_MOVE_M] and at least [MIN_INTERVAL_MS] has passed, so a parked or
 *    fast-spinning robot doesn't spam the VLM.
 *  - **conversation-aware** — it skips a tick while [Agent.isChatting] so a
 *    live conversation always has the camera + LLM to itself.
 *
 * Started/stopped from [DroidalTools.exploreMode] (and stopped by
 * [DroidalTools.freeze]) so it tracks the same on/off state the robot uses.
 */
object ExplorationCapture {

    private const val TAG = "ExplorationCapture"
    private const val TICK_MS = 4000L
    private const val MIN_INTERVAL_MS = 8000L
    private const val MIN_MOVE_M = 0.75

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile private var job: Job? = null
    private var lastX = 0.0
    private var lastY = 0.0
    private var lastCaptureAt = 0L

    val isRunning: Boolean get() = job?.isActive == true

    @Synchronized
    fun start() {
        if (isRunning) return
        lastCaptureAt = 0L
        job = scope.launch { loop() }
        Log.i(TAG, "auto-capture started")
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        Log.i(TAG, "auto-capture stopped")
    }

    private suspend fun loop() {
        while (scope.isActive && job?.isActive == true) {
            delay(TICK_MS)
            runCatching { tick() }.onFailure { Log.w(TAG, "tick failed: ${it.message}") }
        }
    }

    private suspend fun tick() {
        if (Agent.isChatting()) return
        val pose = RobotHttpClient.pose() ?: return
        val now = System.currentTimeMillis()
        val firstCapture = lastCaptureAt == 0L
        val moved = ObjectLocalizer.distance(pose.x, pose.y, lastX, lastY)
        if (!firstCapture && (now - lastCaptureAt < MIN_INTERVAL_MS || moved < MIN_MOVE_M)) return

        val outcome = SpatialMemory.captureAndStore()
        lastX = pose.x
        lastY = pose.y
        lastCaptureAt = now
        Log.i(TAG, "auto-capture: seen=${outcome.seen.size} stored=${outcome.stored.size}")
    }
}
