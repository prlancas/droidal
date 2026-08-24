package com.prlancas.droidal.brain.tools

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Request/response HTTP link to the ROS 2 `android_bridge.py` server.
 *
 * The sibling [RobotBridge] uses UDP for tiny fire-and-forget safety commands
 * (explore / freeze). This client covers everything that needs a reply or a
 * larger payload for the spatial-memory features:
 *
 *  - [pose] / [scan] / [mapMetadata] — read robot state to localise objects.
 *  - [goal] / [cancelGoal] — drive Nav2 to a map coordinate ("go to the cooker").
 *  - [pushObjects] — mirror the phone's object landmarks to the host visualiser.
 *
 * HTTP can't be broadcast, so unlike [RobotBridge] this needs a concrete host
 * IP: when [SettingsRepository.robotBridgeHost] is still the broadcast default
 * every call fails fast with `null` and a one-line log, and the calling tool
 * tells the user to set the robot host (debug `robot host <ip>`).
 */
object RobotHttpClient {

    private const val TAG = "RobotHttpClient"
    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 6000

    data class RobotPose(val x: Double, val y: Double, val yaw: Double, val stamp: Double)

    data class LaserScanSnapshot(
        val angleMin: Double,
        val angleMax: Double,
        val angleIncrement: Double,
        val rangeMin: Double,
        val rangeMax: Double,
        /** One entry per beam; `null` where the beam had no return (inf/nan). */
        val ranges: List<Double?>,
    )

    data class MapMetadata(
        val resolution: Double,
        val width: Int,
        val height: Int,
        val originX: Double,
        val originY: Double,
        val originYaw: Double,
    )

    /** True when a concrete host is configured (HTTP can't use the UDP broadcast default). */
    fun isConfigured(): Boolean = baseUrl() != null

    private fun baseUrl(): String? {
        val settings = runCatching { SettingsRepository.get(Config.getContext()) }.getOrNull()
        val host = settings?.robotBridgeHost() ?: SettingsRepository.DEFAULT_ROBOT_BRIDGE_HOST
        if (host == SettingsRepository.DEFAULT_ROBOT_BRIDGE_HOST) {
            // Broadcast address — unusable for HTTP.
            return null
        }
        val port = settings?.robotBridgeHttpPort() ?: SettingsRepository.DEFAULT_ROBOT_BRIDGE_HTTP_PORT
        return "http://$host:$port"
    }

    suspend fun pose(): RobotPose? = getJson("/pose")?.let { o ->
        runCatching {
            RobotPose(
                x = o.get("x").asDouble,
                y = o.get("y").asDouble,
                yaw = o.get("yaw").asDouble,
                stamp = o.get("stamp")?.asDouble ?: 0.0,
            )
        }.getOrNull()
    }

    suspend fun scan(): LaserScanSnapshot? = getJson("/scan")?.let { o ->
        runCatching {
            val ranges = o.getAsJsonArray("ranges").map { el ->
                if (el.isJsonNull) null else el.asDouble
            }
            LaserScanSnapshot(
                angleMin = o.get("angle_min").asDouble,
                angleMax = o.get("angle_max").asDouble,
                angleIncrement = o.get("angle_increment").asDouble,
                rangeMin = o.get("range_min").asDouble,
                rangeMax = o.get("range_max").asDouble,
                ranges = ranges,
            )
        }.getOrNull()
    }

    suspend fun mapMetadata(): MapMetadata? = getJson("/map.json")?.let { o ->
        runCatching {
            val origin = o.getAsJsonObject("origin")
            MapMetadata(
                resolution = o.get("resolution").asDouble,
                width = o.get("width").asInt,
                height = o.get("height").asInt,
                originX = origin.get("x").asDouble,
                originY = origin.get("y").asDouble,
                originYaw = origin.get("yaw").asDouble,
            )
        }.getOrNull()
    }

    /** Send a Nav2 goal in the map frame. Returns true if the host accepted it. */
    suspend fun goal(x: Double, y: Double, yaw: Double = 0.0): Boolean {
        val body = JsonObject().apply {
            addProperty("x", x)
            addProperty("y", y)
            addProperty("yaw", yaw)
        }
        return postJson("/goal", body) != null
    }

    suspend fun cancelGoal(): Boolean = postJson("/goal/cancel", JsonObject()) != null

    /**
     * Mirror object landmarks to the host so the web visualiser can show them.
     * Best-effort: failures are logged and swallowed (the phone DB is the source
     * of truth). Returns the number the host reports stored, or null on failure.
     */
    suspend fun pushObjects(objects: List<JsonObject>): Int? {
        if (objects.isEmpty()) return 0
        val arr = JsonArray().apply { objects.forEach { add(it) } }
        val body = JsonObject().apply { add("objects", arr) }
        return postJson("/objects", body)?.get("stored")?.asInt
    }

    private suspend fun getJson(path: String): JsonObject? = withContext(Dispatchers.IO) {
        val base = baseUrl() ?: run {
            Log.w(TAG, "No robot host configured (still broadcast default); GET $path skipped")
            return@withContext null
        }
        runCatching {
            val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "GET $path -> HTTP ${conn.responseCode}")
                    return@runCatching null
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                Gson().fromJson(text, JsonObject::class.java)
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.w(TAG, "GET $path failed: ${it.message}") }.getOrNull()
    }

    private suspend fun postJson(path: String, body: JsonObject): JsonObject? =
        withContext(Dispatchers.IO) {
            val base = baseUrl() ?: run {
                Log.w(TAG, "No robot host configured (still broadcast default); POST $path skipped")
                return@withContext null
            }
            runCatching {
                val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                try {
                    OutputStreamWriter(conn.outputStream).use {
                        it.write(Gson().toJson(body))
                        it.flush()
                    }
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                        Log.w(TAG, "POST $path -> HTTP ${conn.responseCode}")
                        return@runCatching null
                    }
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    Gson().fromJson(text, JsonObject::class.java)
                } finally {
                    conn.disconnect()
                }
            }.onFailure { Log.w(TAG, "POST $path failed: ${it.message}") }.getOrNull()
        }
}
