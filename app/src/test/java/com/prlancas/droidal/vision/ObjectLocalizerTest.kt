package com.prlancas.droidal.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class ObjectLocalizerTest {

    /** A uniform scan: 360 beams from -180..+180 deg, all reading [range]. */
    private fun uniformScan(range: Double, beams: Int = 360): Triple<Double, Double, List<Double?>> {
        val angleMin = -PI
        val inc = 2 * PI / beams
        return Triple(angleMin, inc, List(beams) { range })
    }

    @Test
    fun `object dead ahead uses lidar range straight in front`() {
        val (min, inc, ranges) = uniformScan(2.0)
        val r = ObjectLocalizer.localize(
            poseX = 0.0, poseY = 0.0, poseYaw = 0.0,
            bboxCenterX = 0.5f, // centre of frame -> straight ahead
            scanAngleMin = min, scanAngleIncrement = inc, scanRanges = ranges,
            hfovDeg = 60.0, yawOffsetDeg = 0.0,
        )
        assertTrue(r.usedRange)
        assertEquals(2.0, r.worldX, 1e-3) // 2 m ahead along +x (yaw 0)
        assertEquals(0.0, r.worldY, 1e-3)
    }

    @Test
    fun `object to the right of frame lands to the robot's right`() {
        val (min, inc, ranges) = uniformScan(3.0)
        val r = ObjectLocalizer.localize(
            poseX = 0.0, poseY = 0.0, poseYaw = 0.0,
            bboxCenterX = 1.0f, // far right of frame
            scanAngleMin = min, scanAngleIncrement = inc, scanRanges = ranges,
            hfovDeg = 60.0, yawOffsetDeg = 0.0,
        )
        // Right of centre is a negative (clockwise) bearing -> negative y.
        assertTrue("expected object to the right (y<0), got ${r.worldY}", r.worldY < 0.0)
        assertTrue(r.worldX > 0.0)
    }

    @Test
    fun `robot heading rotates the world position`() {
        val (min, inc, ranges) = uniformScan(2.0)
        val r = ObjectLocalizer.localize(
            poseX = 1.0, poseY = 1.0, poseYaw = PI / 2, // facing plus y
            bboxCenterX = 0.5f,
            scanAngleMin = min, scanAngleIncrement = inc, scanRanges = ranges,
            hfovDeg = 60.0, yawOffsetDeg = 0.0,
        )
        assertEquals(1.0, r.worldX, 1e-3)
        assertEquals(3.0, r.worldY, 1e-3) // 2 m ahead along +y from (1,1)
    }

    @Test
    fun `no bbox and no scan falls back to capture pose`() {
        val r = ObjectLocalizer.localize(
            poseX = 4.0, poseY = -2.0, poseYaw = 0.3,
            bboxCenterX = null,
            scanAngleMin = null, scanAngleIncrement = null, scanRanges = null,
            hfovDeg = 60.0, yawOffsetDeg = 0.0,
        )
        assertFalse(r.usedRange)
        assertEquals(4.0, r.worldX, 1e-9)
        assertEquals(-2.0, r.worldY, 1e-9)
    }

    @Test
    fun `bbox but no valid range uses fallback distance`() {
        val r = ObjectLocalizer.localize(
            poseX = 0.0, poseY = 0.0, poseYaw = 0.0,
            bboxCenterX = 0.5f,
            scanAngleMin = null, scanAngleIncrement = null, scanRanges = null,
            hfovDeg = 60.0, yawOffsetDeg = 0.0,
            fallbackRangeM = 1.5,
        )
        assertFalse(r.usedRange)
        assertEquals(1.5, r.worldX, 1e-3)
        assertEquals(0.0, r.worldY, 1e-3)
    }

    @Test
    fun `range dropout is filled from a neighbouring beam`() {
        val (min, inc, ranges) = uniformScan(2.0)
        val mutable = ranges.toMutableList()
        // Punch a hole at the straight-ahead beam; neighbour should be used.
        val aheadIdx = ((0.0 - min) / inc).toInt()
        mutable[aheadIdx] = null
        val r = ObjectLocalizer.localize(
            poseX = 0.0, poseY = 0.0, poseYaw = 0.0,
            bboxCenterX = 0.5f,
            scanAngleMin = min, scanAngleIncrement = inc, scanRanges = mutable,
            hfovDeg = 60.0, yawOffsetDeg = 0.0,
        )
        assertTrue(r.usedRange)
        assertEquals(2.0, r.worldX, 1e-2)
    }
}
