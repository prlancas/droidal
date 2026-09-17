package com.prlancas.droidal.settings

import com.prlancas.droidal.settings.SettingsRepository.Companion.DEFAULT_CAMERA_HFOV_DEG
import com.prlancas.droidal.settings.SettingsRepository.Companion.DEFAULT_CAMERA_STREAM_ENABLED
import com.prlancas.droidal.settings.SettingsRepository.Companion.DEFAULT_CAMERA_STREAM_FPS
import com.prlancas.droidal.settings.SettingsRepository.Companion.DEFAULT_CAMERA_YAW_OFFSET_DEG
import com.prlancas.droidal.settings.SettingsRepository.Companion.DEFAULT_ROBOT_BRIDGE_HOST
import com.prlancas.droidal.settings.SettingsRepository.Companion.DEFAULT_ROBOT_BRIDGE_WS_PORT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RobotBridgeSettingsTest {

    @Test
    fun `default robot bridge host is broadcast`() {
        assertEquals("255.255.255.255", DEFAULT_ROBOT_BRIDGE_HOST)
    }

    @Test
    fun `default robot bridge WebSocket port is 8791`() {
        assertEquals(8791, DEFAULT_ROBOT_BRIDGE_WS_PORT)
    }

    @Test
    fun `default camera calibration values`() {
        assertEquals(66.0f, DEFAULT_CAMERA_HFOV_DEG, 0.001f)
        assertEquals(0.0f, DEFAULT_CAMERA_YAW_OFFSET_DEG, 0.001f)
    }

    @Test
    fun `default camera stream values`() {
        assertFalse(DEFAULT_CAMERA_STREAM_ENABLED)
        assertEquals(0.5f, DEFAULT_CAMERA_STREAM_FPS, 0.001f)
    }
}
