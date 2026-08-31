package com.prlancas.droidal.brain.tools

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.prlancas.droidal.brain.llm.ImageDescriber
import com.prlancas.droidal.camera.CameraManager
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import com.prlancas.droidal.memory.learning.LearningStore
import com.prlancas.droidal.settings.SettingsRepository
import com.prlancas.droidal.vision.ObjectLocalizer
import com.prlancas.droidal.vision.ObjectThumbnails
import com.prlancas.droidal.vision.VisionObject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The end-to-end "see, localise, remember" pipeline shared by the
 * `whatDoYouSee` tool and the exploration auto-capture loop.
 *
 * 1. Capture a frame from the camera.
 * 2. Ask the VLM for a structured [VisionObject] list.
 * 3. Read the robot pose + LiDAR scan over HTTP and localise each object on the
 *    map with [ObjectLocalizer].
 * 4. Store/merge each landmark in the per-user object DB with a thumbnail.
 * 5. Best-effort mirror the landmarks to the ROS host for the visualiser.
 *
 * Everything is best-effort: no camera, no VLM output, or no robot pose each
 * degrade to a clear [Outcome] the caller can turn into speech.
 */
object SpatialMemory {

    private const val TAG = "SpatialMemory"

    /**
     * Object landmarks describe one shared physical place, so they're all filed
     * under this single bucket rather than the per-conversation user id (which
     * would be "unknown" during headless exploration and invisible to a later
     * recognised-user conversation). The object DB is keyed by "userId" like the
     * rest of the learning store, so we reuse that column with a fixed key.
     */
    const val SPATIAL_USER = "environment"

    data class Stored(
        val uuid: String,
        val canonical: String,
        val label: String,
        val worldX: Double,
        val worldY: Double,
        val isDoor: Boolean,
    )

    /**
     * @param seen everything the VLM recognised (even if it couldn't be mapped).
     * @param stored the subset localised + written to the DB.
     * @param mapped false when there was no robot pose, so nothing could be
     *   placed on the map (the objects were still "seen").
     * @param error a user-facing failure reason, or null on success.
     */
    data class Outcome(
        val seen: List<VisionObject>,
        val stored: List<Stored>,
        val mapped: Boolean,
        val error: String?,
    )

    suspend fun captureAndStore(userId: String = SPATIAL_USER): Outcome {
        val context = Config.getContext()
        val camera = CameraManager.instance
            ?: return Outcome(emptyList(), emptyList(), mapped = false, error = "I can't access the camera right now.")

        val bitmap = captureImageSuspend(camera)
            ?: return Outcome(emptyList(), emptyList(), mapped = false, error = "I couldn't capture an image.")

        val pose = RobotWsClient.pose()
        val rawSeen = ImageDescriber(context).describeObjects(bitmap)

        // If no distinct object is found, record a "wall" entry so every stretch of wall is captured.
        val seen = rawSeen.ifEmpty {
            listOf(
                VisionObject(
                    label = "Wall",
                    canonical = "wall",
                    aliases = emptyList(),
                    bboxNorm = null,
                    confidence = 0.5f,
                    isDoor = false,
                ),
            )
        }

        if (pose == null) {
            // We saw things but can't place them without a map pose.
            return Outcome(seen, emptyList(), mapped = false, error = null)
        }

        // Live narration of findings
        val s = SettingsRepository.get(context)
        if (s.liveNarrationEnabled()) {
            val nonWalls = seen.filter { it.canonical != "wall" }
            if (nonWalls.isNotEmpty()) {
                val descriptions = nonWalls.map { obj ->
                    if (obj.aliases.isNotEmpty()) {
                        "I see a ${obj.label}, also known as ${obj.aliases.joinToString(", ")}"
                    } else {
                        "I see a ${obj.label}"
                    }
                }
                EventBus.publishAsync(Say(descriptions.joinToString(". ")))
            } else {
                EventBus.publishAsync(Say("I see a wall"))
            }
        }

        val scan = RobotWsClient.scan()
        val hfov = s.cameraHfovDeg().toDouble()
        val yawOffset = s.cameraYawOffsetDeg().toDouble()
        val store = LearningStore.get(context)

        val stored = seen.map { obj ->
            storeOne(context, store, userId, obj, bitmap, pose, scan, hfov, yawOffset)
        }

        RoomTracker.recordObservation(pose.x, pose.y, seen.map { it.canonical })
        pushToHost(stored, pose, seen)
        Log.i(TAG, "captureAndStore: seen=${seen.size} stored=${stored.size}")
        return Outcome(seen, stored, mapped = true, error = null)
    }

    @Suppress("LongParameterList")
    private fun storeOne(
        context: Context,
        store: LearningStore,
        userId: String,
        obj: VisionObject,
        bitmap: Bitmap,
        pose: RobotWsClient.RobotPose,
        scan: RobotWsClient.LaserScanSnapshot?,
        hfov: Double,
        yawOffset: Double,
    ): Stored {
        val loc = ObjectLocalizer.localize(
            poseX = pose.x,
            poseY = pose.y,
            poseYaw = pose.yaw,
            bboxCenterX = obj.bboxCenterX(),
            scanAngleMin = scan?.angleMin,
            scanAngleIncrement = scan?.angleIncrement,
            scanRanges = scan?.ranges,
            hfovDeg = hfov,
            yawOffsetDeg = yawOffset,
        )
        val uuid = store.objectDao.upsertObject(
            userId = userId,
            canonical = obj.canonical,
            label = obj.label,
            aliases = obj.aliases,
            worldX = loc.worldX,
            worldY = loc.worldY,
            sourceX = pose.x,
            sourceY = pose.y,
            sourceYaw = pose.yaw,
            confidence = obj.confidence.toDouble(),
            isDoor = obj.isDoor,
            thumbPath = null,
        )
        runCatching {
            val thumb = ObjectThumbnails.thumbnail(bitmap, obj.bboxNorm)
            ObjectThumbnails.save(context, uuid, thumb)?.let { store.objectDao.updateThumbPath(uuid, it) }
        }.onFailure { Log.w(TAG, "thumb for $uuid failed: ${it.message}") }
        return Stored(uuid, obj.canonical, obj.label, loc.worldX, loc.worldY, obj.isDoor)
    }

    /** Mirror the freshly stored landmarks (with thumbnails) to the ROS host. */
    private suspend fun pushToHost(
        stored: List<Stored>,
        pose: RobotWsClient.RobotPose,
        seen: List<VisionObject>,
    ) {
        if (stored.isEmpty()) return
        val context = Config.getContext()
        val byUuid = stored.associateBy { it.uuid }
        val records = stored.mapNotNull { s ->
            val obj = seen.firstOrNull { it.canonical == s.canonical }
            JsonObject().apply {
                addProperty("id", s.uuid)
                addProperty("canonical", s.canonical)
                addProperty("label", s.label)
                add("aliases", JsonArray().apply { obj?.aliases?.forEach { add(it) } })
                addProperty("worldX", s.worldX)
                addProperty("worldY", s.worldY)
                addProperty("sourceX", pose.x)
                addProperty("sourceY", pose.y)
                addProperty("sourceYaw", pose.yaw)
                addProperty("isDoor", s.isDoor)
                addProperty("createdAt", System.currentTimeMillis())
                thumbBase64(context, s.uuid)?.let { addProperty("thumbBase64", it) }
            }.takeIf { byUuid.containsKey(s.uuid) }
        }
        runCatching { RobotWsClient.pushObjects(records) }
            .onFailure { Log.w(TAG, "pushObjects failed: ${it.message}") }
    }

    private fun thumbBase64(context: Context, uuid: String): String? = runCatching {
        val dir = context.getExternalFilesDir("object-thumbs") ?: return null
        val file = java.io.File(dir, "$uuid.jpg")
        if (!file.exists()) return null
        android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
    }.getOrNull()

    private suspend fun captureImageSuspend(camera: CameraManager): Bitmap? =
        suspendCancellableCoroutine { cont ->
            runCatching {
                camera.captureImage { bitmap -> if (cont.isActive) cont.resume(bitmap) }
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
}
