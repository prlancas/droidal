package com.prlancas.droidal.brain.tools

import android.content.Context
import android.util.Log
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import com.prlancas.droidal.memory.learning.LearningStore
import com.prlancas.droidal.settings.SettingsRepository
import kotlin.math.hypot

/**
 * Tracks the robot's spatial room location, detects door transitions, classifies
 * rooms on departure, and persists room dimensions to SQLite.
 */
object RoomTracker {

    private const val TAG = "RoomTracker"
    private const val DOOR_TRIGGER_DIST_M = 1.0
    private const val MIN_ROOM_DIAMETER_M = 1.5

    data class RoomTransition(
        val leavingRoom: String,
        val enteringRoom: String,
    )

    private val lock = Any()

    private var currentRoomName: String = "unknown room"
    private var currentRoomLabel: String = "Unknown room"
    private var currentRoomUuid: String? = null
    private val currentVantages = mutableListOf<Pair<Double, Double>>()
    private val currentObjects = mutableSetOf<String>()

    private var lastDoorIdNear: String? = null
    private var lastDoorCrossTime: Long = 0L

    fun currentRoom(): String = synchronized(lock) { currentRoomName }

    fun recordObservation(x: Double, y: Double, canonicalObjects: Collection<String>) {
        synchronized(lock) {
            currentVantages.add(Pair(x, y))
            currentObjects.addAll(canonicalObjects.filter { it.isNotBlank() && it != "wall" })
        }
    }

    /**
     * Called whenever robot pose is updated. Detects whether the robot is
     * crossing a door landmark.
     */
    fun checkPose(poseX: Double, poseY: Double): RoomTransition? {
        val context = Config.getContext()
        val store = LearningStore.get(context)
        val doors = store.objectDao.all(SpatialMemory.SPATIAL_USER).filter { it.isDoor }

        val nearestDoor = doors.map { door ->
            Pair(door, hypot(door.worldX - poseX, door.worldY - poseY))
        }.filter { it.second <= DOOR_TRIGGER_DIST_M }
            .minByOrNull { it.second }

        val now = System.currentTimeMillis()
        synchronized(lock) {
            currentVantages.add(Pair(poseX, poseY))

            if (nearestDoor != null) {
                val door = nearestDoor.first
                // Check if we just crossed through this door from our previous position
                if (door.uuid != lastDoorIdNear && (now - lastDoorCrossTime > 5000L)) {
                    lastDoorIdNear = door.uuid
                    lastDoorCrossTime = now

                    val leavingName = currentRoomName
                    // 1. Finalize and save the departing room if it had observations
                    finalizeCurrentRoom(context, store)

                    // 2. Check if the new area matches an already classified room in DB
                    val existingRoom = store.roomDao.findRoomAt(SpatialMemory.SPATIAL_USER, poseX, poseY)
                    val enteringName = existingRoom?.name ?: "unknown room"
                    val enteringLabel = existingRoom?.label ?: "Unknown room"

                    currentRoomName = enteringName
                    currentRoomLabel = enteringLabel
                    currentRoomUuid = existingRoom?.uuid
                    currentVantages.clear()
                    currentVantages.add(Pair(poseX, poseY))
                    currentObjects.clear()
                    if (existingRoom != null) {
                        currentObjects.addAll(existingRoom.objects)
                    }

                    val transition = RoomTransition(leavingRoom = leavingName, enteringRoom = enteringName)
                    Log.i(TAG, "Door crossed (${door.label}): Leaving $leavingName -> Entering $enteringName")

                    narrateTransition(leavingName, enteringName)
                    return transition
                }
            } else {
                lastDoorIdNear = null
            }
        }
        return null
    }

    /**
     * Finalizes the current room, classifies it with [RoomClassifier], and
     * saves its dimensions to the SQLite database.
     */
    fun finalizeCurrentRoom(context: Context = Config.getContext(), store: LearningStore = LearningStore.get(context)) {
        synchronized(lock) {
            if (currentVantages.size < 2 && currentObjects.isEmpty()) return

            val minX = currentVantages.minOfOrNull { it.first } ?: 0.0
            val maxX = currentVantages.maxOfOrNull { it.first } ?: 0.0
            val minY = currentVantages.minOfOrNull { it.second } ?: 0.0
            val maxY = currentVantages.maxOfOrNull { it.second } ?: 0.0

            val classified = RoomClassifier.classify(currentObjects)
            val nameToSave = if (currentRoomName != "unknown room" && currentRoomName != "room") {
                currentRoomName
            } else {
                classified.name
            }
            val labelToSave = if (currentRoomLabel != "Unknown room" && currentRoomLabel != "Room") {
                currentRoomLabel
            } else {
                classified.label
            }

            // Only save if the bounding box has a plausible area or objects were detected
            if (currentObjects.isNotEmpty() || (maxX - minX >= MIN_ROOM_DIAMETER_M || maxY - minY >= MIN_ROOM_DIAMETER_M)) {
                val uuid = store.roomDao.upsertRoom(
                    userId = SpatialMemory.SPATIAL_USER,
                    name = nameToSave,
                    label = labelToSave,
                    minX = minX - 0.5,
                    minY = minY - 0.5,
                    maxX = maxX + 0.5,
                    maxY = maxY + 0.5,
                    objects = currentObjects.toList(),
                )
                currentRoomUuid = uuid
                currentRoomName = nameToSave
                currentRoomLabel = labelToSave
                Log.i(TAG, "Saved room '$labelToSave' ($uuid) at bounds [$minX,$minY to $maxX,$maxY]")
            }
        }
    }

    fun narrateIfEnabled(text: String) {
        val s = SettingsRepository.get(Config.getContext())
        if (s.liveNarrationEnabled()) {
            EventBus.publishAsync(Say(text))
        }
    }

    private fun narrateTransition(leaving: String, entering: String) {
        val s = SettingsRepository.get(Config.getContext())
        if (s.liveNarrationEnabled()) {
            val leavingMsg = if (leaving != "unknown room") "Leaving $leaving" else "Leaving room"
            val enteringMsg = if (entering != "unknown room") "Entering $entering" else "Entering unknown room"
            EventBus.publishAsync(Say("$leavingMsg. $enteringMsg."))
        }
    }
}
