package com.prlancas.droidal.CommandListener

import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.Collections

object CommandListener {
    init {
        var thr = Thread {
            var serverSocket = ServerSocket(6666);
            runBlocking {
                withContext(Dispatchers.IO) {
//                    launch {
                        EventBus.publish(Say("My address is ${getIp().substringAfterLast('.')}"))
//                    }
                }
            }

            while (true) {
                var socket = serverSocket.accept()
                while (true) {
                    var line: String? =
                        BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
                            ?: break
                    if (line != null) {
                        runBlocking {
                            withContext(Dispatchers.IO) {
//                                launch {
                                    EventBus.publish(Say(line))

//                                }
                            }
                        }
                    }
                }
            }
        }
        thr.start();
    }

    private fun getIp(): String {
        try {
            var interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress
                        if (sAddr.indexOf(':') < 0)
                            return sAddr;
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } // for now eat exceptions
        return "Unknown";
    }
}