package com.prlancas.droidal.debug

import android.util.Log
import com.prlancas.droidal.brain.llm.ImageDescriber
import com.prlancas.droidal.brain.tools.ExplorationCapture
import com.prlancas.droidal.brain.tools.RobotWsClient
import com.prlancas.droidal.camera.CameraManager
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Look
import com.prlancas.droidal.event.events.OpenSettings
import com.prlancas.droidal.event.events.Say
import com.prlancas.droidal.memory.learning.LearningPaths
import com.prlancas.droidal.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale
import kotlin.coroutines.resume

object DebugHandle {

    var echoBackEnabled = false
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun debugCommand(command: String) {
        val subCommand = command.lowercase(Locale.UK).substringAfter("debug").trim()
        if (handleRobotCommand(subCommand)) return
        when {
            subCommand == "ip" ->
                EventBus.publishAsync(Say("My address is ${getIp()}"))

            subCommand == "hello" ->
                EventBus.publishAsync(Say("Hello there!"))

            subCommand == "echo" -> {
                echoBackEnabled = !echoBackEnabled
                val status = if (echoBackEnabled) "enabled" else "disabled"
                EventBus.publishAsync(Say("Echo back $status"))
            }

            subCommand == "look sleepy" -> {
                EventBus.publishAsync(Look(0.0f, 0.0f, com.prlancas.droidal.event.events.Expression.SLEEPY))
                EventBus.publishAsync(Say("Looking sleepy"))
            }

            subCommand == "blink" -> {
                EventBus.publishAsync(Look(0.0f, 0.0f, com.prlancas.droidal.event.events.Expression.BLINK))
                EventBus.publishAsync(Say("Blinking"))
            }

            subCommand == "think" -> {
                EventBus.publishAsync(Look(0.0f, 0.0f, com.prlancas.droidal.event.events.Expression.THINKING))
                EventBus.publishAsync(Say("Thinking"))
            }

            subCommand == "sleep" -> {
                EventBus.publishAsync(Look(0.0f, 0.0f, com.prlancas.droidal.event.events.Expression.SLEEP))
                EventBus.publishAsync(Say("Going to sleep"))
            }

            subCommand == "look normal" -> {
                EventBus.publishAsync(Look(0.0f, 0.0f, com.prlancas.droidal.event.events.Expression.NORMAL))
                EventBus.publishAsync(Say("Looking normal"))
            }

            subCommand == "look cute" -> {
                EventBus.publishAsync(Look(0.0f, 0.0f, com.prlancas.droidal.event.events.Expression.CUTE))
                EventBus.publishAsync(Say("Looking cute"))
            }

            subCommand == "look bloodshot" -> {
                EventBus.publishAsync(Look(0.0f, 0.0f, com.prlancas.droidal.event.events.Expression.BLOODSHOT))
                EventBus.publishAsync(Say("Looking bloodshot"))
            }

            subCommand == "what can you see" || subCommand == "what do you see" ->
                handleWhatCanYouSee()

            subCommand == "settings" -> {
                EventBus.publishAsync(Say("Opening settings"))
                EventBus.publishAsync(OpenSettings)
            }

            subCommand == "who" || subCommand == "current user" ->
                handleWho()

            subCommand == "clear user" || subCommand == "forget user" ->
                handleClearUser()

            subCommand.startsWith("set user ") ->
                handleSetUser(subCommand.removePrefix("set user").trim())

            else -> {
                val help = "Debug command not found. Supported commands are: ip, hello, echo, " +
                    "look sleepy, blink, think, sleep, look normal, look cute, look bloodshot, " +
                    "what can you see, explore, explore off, freeze, robot ping, settings, " +
                    "set user <name>, clear user, who. I heard: $subCommand"
                EventBus.publishAsync(Say(help))
            }
        }
    }

    /**
     * Robot / ROS-bridge debug commands, split out of [debugCommand] so the
     * big command `when` stays under detekt's complexity budget. Returns
     * true when [subCommand] was a robot command (and has been handled).
     */
    private fun handleRobotCommand(subCommand: String): Boolean {
        when {
            subCommand == "explore" || subCommand == "explore on" -> {
                RobotWsClient.explore(true)
                ExplorationCapture.start()
                EventBus.publishAsync(Say("Exploring"))
            }

            subCommand == "explore off" || subCommand == "stop exploring" -> {
                RobotWsClient.explore(false)
                ExplorationCapture.stop()
                EventBus.publishAsync(Say("Stopped exploring"))
            }

            subCommand == "freeze" || subCommand == "stop" -> {
                RobotWsClient.freeze()
                ExplorationCapture.stop()
                EventBus.publishAsync(Say("Freezing"))
            }

            subCommand == "robot ping" -> handleRobotPing()

            else -> return false
        }
        return true
    }

    /**
     * "robot ping": query the WS bridge's `/pose` endpoint and report whether
     * the ROS host is reachable and where it thinks the robot is. Confirms the
     * bidirectional link end-to-end without needing Foxglove.
     */
    private fun handleRobotPing() {
        if (!RobotWsClient.isConfigured()) {
            val msg = "No robot host is set. Configure the robot host in settings to point me at the ROS server."
            EventBus.publishAsync(Say(msg))
            return
        }
        scope.launch {
            val pose = RobotWsClient.pose()
            if (pose == null) {
                EventBus.publishAsync(Say("I couldn't reach the robot over WebSocket."))
            } else {
                val where = "Robot is at x ${"%.1f".format(pose.x)}, y ${"%.1f".format(pose.y)} metres."
                EventBus.publishAsync(Say(where))
            }
        }
    }

    /**
     * Parses a "set user <name>" debug command, persists the resulting
     * sanitised user id in [SettingsRepository.setCurrentUserOverride],
     * and confirms aloud. Empty / whitespace names are rejected.
     */
    private fun handleSetUser(rawName: String) {
        val trimmed = rawName.trim().trim('"', '\'')
        if (trimmed.isEmpty()) {
            EventBus.publishAsync(Say("I need a name. Try, debug set user paul."))
            return
        }
        val sanitised = LearningPaths.sanitize(trimmed)
        SettingsRepository.get(Config.getContext()).setCurrentUserOverride(sanitised)
        EventBus.publishAsync(Say("Got it. From now on you are $trimmed."))
    }

    private fun handleClearUser() {
        SettingsRepository.get(Config.getContext()).setCurrentUserOverride(null)
        EventBus.publishAsync(Say("Cleared the current user. I will fall back to face recognition."))
    }

    private fun handleWho() {
        val override = SettingsRepository.get(Config.getContext()).currentUserOverride()
        if (override.isNullOrBlank()) {
            EventBus.publishAsync(Say("No current user is set. I will use whoever the camera recognises."))
        } else {
            EventBus.publishAsync(Say("The current user is $override."))
        }
    }

    private fun getIp(): String {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filter { !it.isLoopbackAddress }
                .mapNotNull { it.hostAddress }
                .firstOrNull { ':' !in it }
                ?: "Unknown"
        } catch (e: Exception) {
            Log.w("DebugHandle", "Could not enumerate network interfaces", e)
            "Unknown"
        }
    }

    private fun handleWhatCanYouSee() {
        val cameraManager = CameraManager.instance
        if (cameraManager == null) {
            EventBus.publishAsync(Say("Sorry, I can't access the camera right now"))
            return
        }

        EventBus.publishAsync(Say("Let me take a look..."))

        scope.launch {
            try {
                val capturedBitmap = captureImageSuspend(cameraManager)

                if (capturedBitmap == null) {
                    EventBus.publishAsync(Say("Sorry, I couldn't capture an image"))
                    return@launch
                }

                Log.i(
                    "DebugHandle",
                    "what-can-you-see: captured ${capturedBitmap.width}x${capturedBitmap.height} bitmap",
                )
                ConversationLog.append(
                    ConversationLog.Kind.INFO,
                    "what-can-you-see captured ${capturedBitmap.width}x${capturedBitmap.height}",
                )

                val describer = ImageDescriber(Config.getContext())
                val description = describer.describe(capturedBitmap)

                if (description != null) {
                    ConversationLog.append(
                        ConversationLog.Kind.INFO,
                        "what-can-you-see description: ${description.take(120)}",
                    )
                    EventBus.publishAsync(Say(description))
                } else {
                    EventBus.publishAsync(Say("Sorry, I couldn't describe what I see"))
                }
            } catch (e: Exception) {
                Log.e("DebugHandle", "Error in what can you see: ${e.message}", e)
                EventBus.publishAsync(Say("Sorry, something went wrong while trying to see"))
            }
        }
    }

    private suspend fun captureImageSuspend(cameraManager: CameraManager): android.graphics.Bitmap? {
        return suspendCancellableCoroutine { continuation ->
            try {
                cameraManager.captureImage { bitmap ->
                    if (continuation.isActive) {
                        continuation.resume(bitmap)
                    }
                }
            } catch (e: Exception) {
                Log.w("DebugHandle", "captureImage threw before producing a bitmap", e)
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
    }
}
