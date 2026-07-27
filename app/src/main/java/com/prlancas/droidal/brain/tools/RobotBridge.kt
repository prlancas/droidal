package com.prlancas.droidal.brain.tools

import android.util.Log
import com.google.gson.JsonObject
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Fire-and-forget UDP link from the Android app to the robot's ROS 2 stack.
 *
 * The app can't speak DDS directly, so movement commands are sent as small
 * JSON datagrams to the `android_bridge.py` node running on the ROS 2 host
 * (see `rosconfig/mnt/android_bridge.py`). That node translates them into
 * the real ROS topics:
 *
 *  - `{"command":"explore","enable":true|false}` -> `/explore/enable` (Bool)
 *  - `{"command":"freeze"}` -> disable exploration, cancel the active Nav2
 *    goal (`/goal_pose/cancel`) and publish a zero `/cmd_vel` so the base
 *    halts immediately.
 *
 * UDP (not TCP) keeps the client trivial and connectionless — there's no
 * socket to keep alive across the robot rebooting — and lets us broadcast
 * to the whole subnet so no host IP needs configuring on a typical robot
 * LAN. Because a datagram can be dropped, safety-critical commands are sent
 * a few times ([SAFETY_REPEATS]); the ROS side is idempotent.
 */
object RobotBridge {

    private const val TAG = "RobotBridge"
    private const val SAFETY_REPEATS = 3
    private const val REPEAT_GAP_MS = 60L

    private val scope = CoroutineScope(Dispatchers.IO)

    /** Turn autonomous frontier exploration on or off. */
    fun explore(enable: Boolean) {
        val payload = JsonObject().apply {
            addProperty("command", "explore")
            addProperty("enable", enable)
        }
        send(payload, repeats = 1)
    }

    /**
     * Emergency stop / freeze: halt all movement and stop exploring. Sent
     * several times because it's the "something is going wrong" command and
     * a single dropped datagram must not leave the robot driving.
     */
    fun freeze() {
        val payload = JsonObject().apply { addProperty("command", "freeze") }
        send(payload, repeats = SAFETY_REPEATS)
    }

    private fun send(payload: JsonObject, repeats: Int) {
        val settings = runCatching {
            SettingsRepository.get(Config.getContext())
        }.getOrNull()
        val host = settings?.robotBridgeHost() ?: SettingsRepository.DEFAULT_ROBOT_BRIDGE_HOST
        val port = settings?.robotBridgePort() ?: SettingsRepository.DEFAULT_ROBOT_BRIDGE_PORT
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val broadcast = host == SettingsRepository.DEFAULT_ROBOT_BRIDGE_HOST

        scope.launch {
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = broadcast
                    val address = InetAddress.getByName(host)
                    repeat(repeats.coerceAtLeast(1)) { i ->
                        socket.send(DatagramPacket(bytes, bytes.size, address, port))
                        if (i < repeats - 1) Thread.sleep(REPEAT_GAP_MS)
                    }
                }
                Log.i(TAG, "Sent $payload to $host:$port (broadcast=$broadcast, x$repeats)")
            }.onFailure {
                Log.e(TAG, "Failed to send $payload to $host:$port: ${it.message}", it)
            }
        }
    }
}
