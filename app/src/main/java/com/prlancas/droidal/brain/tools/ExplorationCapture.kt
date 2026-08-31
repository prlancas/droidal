package com.prlancas.droidal.brain.tools

import android.util.Log
import com.prlancas.droidal.brain.Agent
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import com.prlancas.droidal.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * App-driven exploration controller for Droidal.
 *
 * Drives Nav2 to systematically explore the environment area-by-area:
 * 1. Completes exploring the current room/area (including photographing every stretch
 *    of wall and clearing local frontiers) before transitioning through doors to new rooms.
 * 2. Commands Nav2 over WebSocket, monitors progress, and handles timeouts.
 * 3. Captures and analyses pictures with the VLM on arrival at each vantage point.
 * 4. Integrates with [RoomTracker] to detect door crossings, classify departed rooms,
 *    and persist room dimensions in SQLite.
 * 5. Provides live speech narration ("Need input", "Moving to X... Y...", "Position reached",
 *    "Leaving kitchen", "Entering hall", etc.) when enabled.
 */
object ExplorationCapture {

    private const val TAG = "ExplorationCapture"
    private const val POLL_INTERVAL_MS = 800L
    private const val GOAL_TIMEOUT_MS = 45_000L
    private const val ARRIVAL_DIST_M = 0.40
    private const val LOCAL_AREA_RADIUS_M = 2.8
    private const val WALL_SAMPLE_MIN_DIST_M = 0.6
    private const val BLACKLIST_RADIUS_M = 0.5

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile private var job: Job? = null
    val isRunning: Boolean get() = job?.isActive == true

    private val visitedVantages = mutableListOf<Pair<Double, Double>>()
    private val blacklistedTargets = mutableListOf<Pair<Double, Double>>()

    @Synchronized
    fun start() {
        if (isRunning) return
        visitedVantages.clear()
        job = scope.launch { explorationLoop() }
        Log.i(TAG, "App-driven exploration started")
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        scope.launch {
            RobotWsClient.cancelGoal()
        }
        RoomTracker.finalizeCurrentRoom()
        Log.i(TAG, "App-driven exploration stopped")
    }

    private suspend fun explorationLoop() {
        while (scope.isActive && (job?.isActive == true)) {
            if (runExplorationStep()) break
        }
    }

    private suspend fun runExplorationStep(): Boolean {
        if (Agent.isChatting()) {
            delay(2.seconds)
            return false
        }

        val currentPose = RobotWsClient.pose() ?: run {
            delay(2.seconds)
            return false
        }
        RoomTracker.checkPose(currentPose.x, currentPose.y)

        // 1. Query candidate exploration targets from ROS bridge
        val targets = RobotWsClient.getExplorationTargets() ?: run {
            delay(2.seconds)
            return false
        }

        // 2. Select next target with area-first priority (local walls & frontiers first, then doors)
        val nextGoal = selectNextTarget(currentPose, targets) ?: run {
            narrate("Exploration complete. All areas explored.")
            Log.i(TAG, "No more reachable exploration targets.")
            stop()
            return true
        }

        // 3. Drive to the target
        val reached = driveToGoal(nextGoal.x, nextGoal.y, nextGoal.yaw)
        if (reached) {
            visitedVantages.add(Pair(nextGoal.x, nextGoal.y))
            onGoalReached()
        } else {
            blacklistedTargets.add(Pair(nextGoal.x, nextGoal.y))
            narrate("Navigation failed. Selecting alternative target.")
        }

        delay(1.seconds)
        return false
    }

    private data class TargetCandidate(
        val x: Double,
        val y: Double,
        val yaw: Double,
        val isWall: Boolean,
        val isDoorway: Boolean,
        val distance: Double,
    )

    private fun selectNextTarget(
        robotPose: RobotWsClient.RobotPose,
        targets: RobotWsClient.ExplorationTargets,
    ): TargetCandidate? {
        val candidates = mutableListOf<TargetCandidate>()

        // A. Wall observation targets
        for (w in targets.wallTargets) {
            if (isBlacklisted(w.x, w.y) || isAlreadyVisited(w.x, w.y, WALL_SAMPLE_MIN_DIST_M)) continue
            val dist = hypot(w.x - robotPose.x, w.y - robotPose.y)
            candidates.add(
                TargetCandidate(
                    x = w.x,
                    y = w.y,
                    yaw = w.yaw,
                    isWall = true,
                    isDoorway = false,
                    distance = dist,
                ),
            )
        }

        // B. Frontiers
        for (f in targets.frontiers) {
            if (isBlacklisted(f.x, f.y) || isAlreadyVisited(f.x, f.y, 0.4)) continue
            val dist = hypot(f.x - robotPose.x, f.y - robotPose.y)
            val yaw = atan2(f.y - robotPose.y, f.x - robotPose.x)
            candidates.add(
                TargetCandidate(
                    x = f.x,
                    y = f.y,
                    yaw = yaw,
                    isWall = false,
                    isDoorway = f.isDoorway,
                    distance = dist,
                ),
            )
        }

        // Priority 1: Local unphotographed walls & local frontiers in the CURRENT area (< LOCAL_AREA_RADIUS_M)
        val localCandidates = candidates.filter { (it.distance <= LOCAL_AREA_RADIUS_M) && (!it.isDoorway) }
        if (localCandidates.isNotEmpty()) {
            // Prefer walls first (to map every stretch of wall), then local frontiers
            return localCandidates.minByOrNull { (if (it.isWall) 0.0 else 1.0) + it.distance * 0.5 }
        }

        // Priority 2: Non-doorway targets anywhere in the reachable area
        val nonDoorCandidates = candidates.filter { !it.isDoorway }
        if (nonDoorCandidates.isNotEmpty()) {
            return nonDoorCandidates.minByOrNull { it.distance }
        }

        // Priority 3: Doorway transitions to explore the next room
        val doorCandidates = candidates.filter { it.isDoorway }
        if (doorCandidates.isNotEmpty()) {
            return doorCandidates.minByOrNull { it.distance }
        }

        // Fallback: any remaining candidate
        return candidates.minByOrNull { it.distance }
    }

    private suspend fun driveToGoal(x: Double, y: Double, yaw: Double): Boolean {
        narrate("Moving to X ${"%.1f".format(x)} Y ${"%.1f".format(y)}")
        val accepted = RobotWsClient.goal(x, y, yaw)
        if (!accepted) {
            Log.w(TAG, "Goal rejected by ROS bridge")
            return false
        }

        val startTime = System.currentTimeMillis()
        while (scope.isActive && (job?.isActive == true)) {
            delay(POLL_INTERVAL_MS.milliseconds)

            val pose = RobotWsClient.pose()
            if (pose != null) {
                RoomTracker.checkPose(pose.x, pose.y)
                val dist = hypot(pose.x - x, pose.y - y)
                if (dist <= ARRIVAL_DIST_M) {
                    Log.i(TAG, "Reached goal (dist=${"%.2f".format(dist)}m)")
                    return true
                }
            }

            val nav = RobotWsClient.getNavStatus()
            nav?.let {
                when (it.status) {
                    "SUCCEEDED" -> return true
                    "ABORTED", "CANCELED" -> {
                        Log.w(TAG, "Nav2 reported ${it.status}")
                        return false
                    }
                }
            }

            if (System.currentTimeMillis() - startTime > GOAL_TIMEOUT_MS) {
                Log.w(TAG, "Navigation timed out after ${GOAL_TIMEOUT_MS / 1000}s")
                RobotWsClient.cancelGoal()
                return false
            }
        }
        return false
    }

    private suspend fun onGoalReached() {
        narrate("Position reached")
        delay(400.milliseconds)

        // Johnny 5 / local LLM visual analysis commentary
        narrate("Need input. Analysing picture with local LLM.")
        delay(300.milliseconds)

        val outcome = SpatialMemory.captureAndStore()
        Log.i(TAG, "Inspection complete: seen=${outcome.seen.size} stored=${outcome.stored.size}")
    }

    private fun isAlreadyVisited(x: Double, y: Double, thresholdM: Double): Boolean =
        visitedVantages.any { hypot(it.first - x, it.second - y) < thresholdM }

    private fun isBlacklisted(x: Double, y: Double): Boolean =
        blacklistedTargets.any { hypot(it.first - x, it.second - y) < BLACKLIST_RADIUS_M }

    private fun narrate(text: String) {
        val s = SettingsRepository.get(Config.getContext())
        if (s.liveNarrationEnabled()) {
            EventBus.publishAsync(Say(text))
        }
    }
}
