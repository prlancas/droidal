package com.prlancas.droidal.brain.tools

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.settings.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Unified WebSocket link from the Android app to the robot's ROS 2 stack.
 *
 * Replaces the former two-transport design (UDP fire-and-forget via
 * `RobotBridge` + HTTP request/response via `RobotHttpClient`) with a
 * single persistent TCP WebSocket connection to the `android_bridge.py`
 * node on the ROS 2 host.
 *
 * ## Protocol
 *
 * All frames are JSON text.
 *
 * **Commands** (fire-and-forget; no reply needed):
 * ```json
 * {"type":"command","command":"explore","enable":true}
 * {"type":"command","command":"freeze"}
 * {"type":"command","command":"ping"}
 * ```
 *
 * **Requests** (coroutine blocks until a matching response or timeout):
 * ```json
 * {"type":"request","id":"<uuid>","method":"GET","path":"/pose"}
 * {"type":"request","id":"<uuid>","method":"POST","path":"/goal",
 *  "body":{"x":1.0,"y":2.0,"yaw":0.0}}
 * ```
 * Server responds with `{"id":"<same>","result":<JSON>}` or
 * `{"id":"<same>","error":"<message>"}`.
 *
 * ## Connection lifecycle
 *
 * The WebSocket is opened lazily on the first send and reconnects
 * automatically with exponential back-off after a disconnect. A host IP
 * *must* be configured in Settings ([SettingsRepository.robotBridgeHost]);
 * all methods fail-fast with `null` / `false` when [isConfigured] is false.
 */
object RobotWsClient {

    private const val TAG = "RobotWsClient"
    private const val REQUEST_TIMEOUT_MS = 8_000L

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

    data class FrontierTarget(
        val x: Double,
        val y: Double,
        val size: Int,
        val isDoorway: Boolean,
    )

    data class WallTarget(
        val x: Double,
        val y: Double,
        val yaw: Double,
        val wallX: Double,
        val wallY: Double,
    )

    data class DoorTarget(
        val id: String,
        val canonical: String,
        val label: String,
        val x: Double,
        val y: Double,
    )

    data class ExplorationTargets(
        val robotPose: RobotPose?,
        val frontiers: List<FrontierTarget>,
        val wallTargets: List<WallTarget>,
        val doors: List<DoorTarget>,
    )

    data class NavStatus(
        val status: String, // IDLE, NAVIGATING, SUCCEEDED, ABORTED, CANCELED
        val targetX: Double?,
        val targetY: Double?,
        val targetYaw: Double?,
        val elapsedSec: Double,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // WS — no read timeout
        .build()

    private val gson = Gson()

    /** Pending requests keyed by message ID, resolved when the server replies. */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    private val socketRef = AtomicReference<WebSocket?>(null)

    /** True when a concrete host is configured (WS cannot use a broadcast address). */
    fun isConfigured(): Boolean = wsUrl() != null

    /**
     * Probe used by the Settings UI "Test" button: connects (or reuses an
     * existing connection) and reads the robot's current pose.
     */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext "Host IP not configured"
        val p = pose()
        if (p != null) {
            "Connected (x=${"%.2f".format(p.x)}, y=${"%.2f".format(p.y)})"
        } else {
            "Failed to reach robot WebSocket server"
        }
    }

    // ---- Commands (fire-and-forget) ----------------------------------------

    /** Turn autonomous frontier exploration on or off. */
    fun explore(enable: Boolean) {
        val payload = JsonObject().apply {
            addProperty("type", "command")
            addProperty("command", "explore")
            addProperty("enable", enable)
        }
        sendFrame(payload.toString())
    }

    /**
     * Emergency stop / freeze: halt all movement and stop exploring.
     * Sent several times because it is the "something is going wrong"
     * command and must not be silently swallowed even on a flaky link.
     */
    fun freeze(repeats: Int = 3) {
        val payload = JsonObject().apply {
            addProperty("type", "command")
            addProperty("command", "freeze")
        }.toString()
        repeat(repeats) { sendFrame(payload) }
    }

    // ---- Requests (suspend, returns null on failure) -----------------------

    suspend fun pose(): RobotPose? = getRequest("/pose")?.let { o ->
        runCatching {
            RobotPose(
                x = o.get("x").asDouble,
                y = o.get("y").asDouble,
                yaw = o.get("yaw").asDouble,
                stamp = o.get("stamp")?.asDouble ?: 0.0,
            )
        }.getOrNull()
    }

    suspend fun scan(): LaserScanSnapshot? = getRequest("/scan")?.let { o ->
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

    suspend fun mapMetadata(): MapMetadata? = getRequest("/map.json")?.let { o ->
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

    suspend fun getNavStatus(): NavStatus? = getRequest("/nav_status")?.let { o ->
        runCatching {
            val target = o.getAsJsonObject("target")
            NavStatus(
                status = o.get("status")?.asString ?: "IDLE",
                targetX = target?.get("x")?.asDouble,
                targetY = target?.get("y")?.asDouble,
                targetYaw = target?.get("yaw")?.asDouble,
                elapsedSec = o.get("elapsed_s")?.asDouble ?: 0.0,
            )
        }.getOrNull()
    }

    suspend fun getExplorationTargets(): ExplorationTargets? = getRequest("/explore/targets")?.let { o ->
        runCatching {
            val robotPose = o.getAsJsonObject("robot_pose")?.let { p ->
                RobotPose(
                    x = p.get("x").asDouble,
                    y = p.get("y").asDouble,
                    yaw = p.get("yaw").asDouble,
                    stamp = p.get("stamp")?.asDouble ?: 0.0,
                )
            }
            val frontiers = o.getAsJsonArray("frontiers")?.mapNotNull { el ->
                val f = el.asJsonObject
                FrontierTarget(
                    x = f.get("x").asDouble,
                    y = f.get("y").asDouble,
                    size = f.get("size")?.asInt ?: 1,
                    isDoorway = f.get("is_doorway")?.asBoolean ?: false,
                )
            }.orEmpty()

            val wallTargets = o.getAsJsonArray("wall_targets")?.mapNotNull { el ->
                val w = el.asJsonObject
                WallTarget(
                    x = w.get("x").asDouble,
                    y = w.get("y").asDouble,
                    yaw = w.get("yaw").asDouble,
                    wallX = w.get("wall_x").asDouble,
                    wallY = w.get("wall_y").asDouble,
                )
            }.orEmpty()

            val doors = o.getAsJsonArray("doors")?.mapNotNull { el ->
                val d = el.asJsonObject
                DoorTarget(
                    id = d.get("id")?.asString.orEmpty(),
                    canonical = d.get("canonical")?.asString ?: "door",
                    label = d.get("label")?.asString ?: "Door",
                    x = d.get("x").asDouble,
                    y = d.get("y").asDouble,
                )
            }.orEmpty()

            ExplorationTargets(
                robotPose = robotPose,
                frontiers = frontiers,
                wallTargets = wallTargets,
                doors = doors,
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
        return postRequest("/goal", body) != null
    }

    suspend fun cancelGoal(): Boolean = postRequest("/goal/cancel", JsonObject()) != null

    /**
     * Mirror object landmarks to the host so the web visualiser can show them.
     * Best-effort: failures are logged and swallowed (the phone DB is the
     * source of truth). Returns the number the host reports stored, or null
     * on failure.
     */
    suspend fun pushObjects(objects: List<JsonObject>): Int? {
        if (objects.isEmpty()) return 0
        val arr = JsonArray().apply { objects.forEach { add(it) } }
        val body = JsonObject().apply { add("objects", arr) }
        return postRequest("/objects", body)?.get("stored")?.asInt
    }

    // ---- Internal helpers --------------------------------------------------

    private fun wsUrl(): String? {
        val settings = runCatching {
            SettingsRepository.get(Config.getContext())
        }.getOrNull()
        val host = settings?.robotBridgeHost() ?: SettingsRepository.DEFAULT_ROBOT_BRIDGE_HOST
        if (host == SettingsRepository.DEFAULT_ROBOT_BRIDGE_HOST) {
            // Broadcast address — unusable for WebSocket.
            return null
        }
        val port = settings?.robotBridgeWsPort() ?: SettingsRepository.DEFAULT_ROBOT_BRIDGE_WS_PORT
        return "ws://$host:$port"
    }

    /** Returns (or opens) a live WebSocket, or null when not configured. */
    private suspend fun socket(): WebSocket? = withContext(Dispatchers.IO) {
        val url = wsUrl() ?: run {
            Log.w(TAG, "No robot host configured; WS call skipped")
            return@withContext null
        }
        socketRef.get()?.let { return@withContext it }

        // Open a new WebSocket.
        val request = Request.Builder().url(url).build()
        val ws = http.newWebSocket(request, Listener())
        // Give it a moment to connect before the first send.
        delay(300)
        ws
    }

    /** Send a raw JSON string; opens the socket if needed. */
    private fun sendFrame(json: String) {
        val ws = socketRef.get()
        if (ws != null) {
            ws.send(json)
        } else {
            // Try to (re)connect in the background; the frame is best-effort.
            val url = wsUrl() ?: return
            val request = Request.Builder().url(url).build()
            val newWs = http.newWebSocket(request, Listener())
            newWs.send(json)
        }
        Log.d(TAG, "TX: $json")
    }

    private suspend fun getRequest(path: String): JsonObject? =
        request("GET", path, null)

    private suspend fun postRequest(path: String, body: JsonObject): JsonObject? =
        request("POST", path, body)

    private suspend fun request(
        method: String,
        path: String,
        body: JsonObject?,
    ): JsonObject? = withContext(Dispatchers.IO) {
        val url = wsUrl() ?: run {
            Log.w(TAG, "No robot host configured; $method $path skipped")
            return@withContext null
        }

        val id = UUID.randomUUID().toString()
        val frame = JsonObject().apply {
            addProperty("type", "request")
            addProperty("id", id)
            addProperty("method", method)
            addProperty("path", path)
            if (body != null) add("body", body)
        }

        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred

        try {
            val ws = socket() ?: run {
                pending.remove(id)
                return@withContext null
            }
            ws.send(gson.toJson(frame))
            Log.d(TAG, "TX request [$id]: $method $path")

            val response = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { deferred.await() }
            if (response == null) {
                Log.w(TAG, "$method $path timed out")
                return@withContext null
            }
            if (response.has("error")) {
                Log.w(TAG, "$method $path error: ${response.get("error")}")
                return@withContext null
            }
            val result = response.get("result")
            if (result == null || result.isJsonNull) return@withContext JsonObject()
            if (result.isJsonObject) return@withContext result.asJsonObject
            // Scalar / array result — wrap it so callers always get a JsonObject.
            JsonObject().apply { add("result", result) }
        } catch (e: Exception) {
            Log.w(TAG, "$method $path failed: ${e.message}")
            null
        } finally {
            pending.remove(id)
        }
    }

    private val navStatusListeners = java.util.concurrent.CopyOnWriteArrayList<(NavStatus) -> Unit>()

    fun addNavStatusListener(listener: (NavStatus) -> Unit) {
        navStatusListeners.add(listener)
    }

    fun removeNavStatusListener(listener: (NavStatus) -> Unit) {
        navStatusListeners.remove(listener)
    }

    // ---- OkHttp WebSocket listener ----------------------------------------

    private class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WS connected to ${webSocket.request().url}")
            socketRef.set(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "RX: $text")
            runCatching {
                val obj = Gson().fromJson(text, JsonObject::class.java)
                val type = obj.get("type")?.asString
                if (type == "event" && obj.get("event")?.asString == "nav_status") {
                    val status = obj.get("status")?.asString ?: "IDLE"
                    val target = obj.getAsJsonObject("target")
                    val navStatus = NavStatus(
                        status = status,
                        targetX = target?.get("x")?.asDouble,
                        targetY = target?.get("y")?.asDouble,
                        targetYaw = target?.get("yaw")?.asDouble,
                        elapsedSec = 0.0,
                    )
                    navStatusListeners.forEach { it(navStatus) }
                    return
                }
                val id = obj.get("id")?.asString ?: return
                pending[id]?.complete(obj)
            }.onFailure {
                Log.w(TAG, "Could not parse WS message: ${it.message}")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WS closing ($code): $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WS closed ($code): $reason")
            socketRef.compareAndSet(webSocket, null)
            failPending("connection closed")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WS failure: ${t.message}")
            socketRef.compareAndSet(webSocket, null)
            failPending(t.message ?: "unknown error")
        }

        private fun failPending(reason: String) {
            val snapshot = pending.values.toList()
            pending.clear()
            val err = JsonObject().apply { addProperty("error", reason) }
            snapshot.forEach { it.complete(err) }
        }
    }
}
