package com.prlancas.droidal.commandlistener

import android.util.Log
import com.prlancas.droidal.debug.DebugHandle
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

/**
 * Tiny TCP debug bridge: anything written to port [PORT] is dispatched
 * either to [DebugHandle] (lines starting with `debug `) or spoken
 * verbatim through [EventBus] / TTS (everything else).
 *
 * Lets a developer drive Droidal from `adb forward tcp:6666 tcp:6666` +
 * `nc localhost 6666` without leaving the laptop. Listens on every
 * interface; only intended for development builds.
 *
 * Bound on the first reference because [com.prlancas.droidal.MainActivity]
 * touches this object after TextToSpeech is ready.
 */
object CommandListener {

    private const val TAG = "CommandListener"
    private const val PORT = 6666

    init {
        Thread({ listenLoop() }, "CommandListener").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Dummy method to trigger initialization.
     */
    fun touch() {
        // No-op
    }

    private fun listenLoop() {
        try {
            ServerSocket(PORT).use { serverSocket ->
                while (!Thread.currentThread().isInterrupted) {
                    serverSocket.accept().use(::serveSocket)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Debug command listener stopped: ${e.message}")
        }
    }

    private fun serveSocket(socket: Socket) {
        BufferedReader(InputStreamReader(socket.getInputStream())).use { reader ->
            generateSequence { reader.readLine() }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach(::dispatch)
        }
    }

    private fun dispatch(line: String) {
        if (line.startsWith("debug", ignoreCase = true)) {
            DebugHandle.debugCommand(line)
        } else {
            EventBus.publishAsync(Say(line))
        }
    }
}
