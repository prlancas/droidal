package com.prlancas.droidal.vision

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Turns "the robot is here, facing this way, and saw an object at this spot in
 * the frame" into a map-frame `(x, y)` for the object.
 *
 * Pure math (no Android / ROS types) so it's covered by fast JVM unit tests.
 * The pipeline that feeds it (capture -> VLM bbox -> pose/scan over HTTP) lives
 * in the tool layer.
 *
 * Method: the object's horizontal bbox centre gives a bearing within the
 * camera's field of view; combined with the camera's yaw offset that's a bearing
 * in the robot's `base_link` frame. We read the LiDAR range at that bearing to
 * get distance, then project from the robot pose in the `map` frame. When the
 * bbox or a valid range is missing we degrade gracefully (see [localize]).
 */
object ObjectLocalizer {

    /**
     * @param usedRange true when a real LiDAR return set the distance; false
     *   when we fell back to [fallbackRangeM] or to the capture pose.
     */
    data class Result(val worldX: Double, val worldY: Double, val usedRange: Boolean)

    private const val DEG_TO_RAD = Math.PI / 180.0

    /** How many neighbouring LiDAR beams to search for a valid return. */
    private const val RANGE_SEARCH_WINDOW = 4

    @Suppress("LongParameterList")
    fun localize(
        poseX: Double,
        poseY: Double,
        poseYaw: Double,
        bboxCenterX: Float?,
        scanAngleMin: Double?,
        scanAngleIncrement: Double?,
        scanRanges: List<Double?>?,
        hfovDeg: Double,
        yawOffsetDeg: Double,
        fallbackRangeM: Double = 1.0,
    ): Result {
        val yawOffset = yawOffsetDeg * DEG_TO_RAD
        val hfov = hfovDeg * DEG_TO_RAD

        // Bearing of the object within the camera frame. Image x grows to the
        // right; ROS yaw grows counter-clockwise (left), so a right-of-centre
        // object (centerX > 0.5) is a negative bearing.
        val bearingInCamera = if (bboxCenterX != null) (0.5 - bboxCenterX) * hfov else 0.0
        val scanAngle = yawOffset + bearingInCamera // in base_link frame

        val range = rangeAt(scanAngle, scanAngleMin, scanAngleIncrement, scanRanges)

        val (dist, usedRange) = when {
            range != null -> range to true
            bboxCenterX == null -> {
                // No bbox and no range: best we can do is the capture pose.
                return Result(poseX, poseY, false)
            }
            else -> fallbackRangeM to false
        }

        val worldBearing = poseYaw + scanAngle
        return Result(
            worldX = poseX + dist * cos(worldBearing),
            worldY = poseY + dist * sin(worldBearing),
            usedRange = usedRange,
        )
    }

    /**
     * LiDAR range (metres) at [angle] rad in the scan frame, or null if no scan
     * or no valid return near that beam. Searches a small window around the
     * exact beam so a single dropout doesn't lose the object.
     */
    private fun rangeAt(
        angle: Double,
        angleMin: Double?,
        angleIncrement: Double?,
        ranges: List<Double?>?,
    ): Double? {
        if (angleMin == null || angleIncrement == null || ranges.isNullOrEmpty() || angleIncrement == 0.0) {
            return null
        }
        val idx = ((angle - angleMin) / angleIncrement).roundToInt()
        for (d in 0..RANGE_SEARCH_WINDOW) {
            for (i in intArrayOf(idx - d, idx + d)) {
                if (i in ranges.indices) {
                    val r = ranges[i]
                    if (r != null && r.isFinite() && r > 0.0) return r
                }
                if (d == 0) break
            }
        }
        return null
    }

    /** Straight-line distance between two map points; handy for tests / dedup. */
    fun distance(ax: Double, ay: Double, bx: Double, by: Double): Double = hypot(ax - bx, ay - by)
}
