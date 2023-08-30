package com.prlancas.droidal.CommandListener

import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.Collections

object CommandListener {
    init {
        val thr = Thread {

            val serverSocket = ServerSocket(6666)
            EventBus.blockPublish(Say("My address is ${getIp().substringAfterLast('.')}"))

            while (true) {
                val socket = serverSocket.accept()
                while (true) {
                    val line: String =
                        BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
                            ?: break
                    EventBus.blockPublish(Say(line))
                }
            }
        }
        thr.start()
    }

    private fun getIp(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress
                        if (sAddr != null) {
                            if (sAddr.indexOf(':') < 0)
                                return sAddr
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } // for now eat exceptions
        return "Unknown"
    }
}