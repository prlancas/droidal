package com.prlancas.droidal.CommandListener

import com.prlancas.droidal.debug.DebugHandle
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

object CommandListener {
    init {
        val thr = Thread {

            val serverSocket = ServerSocket(6666)

            while (true) {
                val socket = serverSocket.accept()
                while (true) {
                    val line: String =
                        BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
                            ?: break
                    
                    // Check if command starts with "debug" (case-insensitive)
                    if (line.trim().startsWith("debug", ignoreCase = true)) {
                        DebugHandle.debugCommand(line.trim())
                    } else {
                        // Fall back to saying the text
                        EventBus.publishAsync(Say(line))
                    }
                }
            }
        }
        thr.start()
    }
}