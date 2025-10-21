package com.prlancas.droidal.debug

import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

object DebugHandle {

    var echoBackEnabled = false

    fun debugCommand(command: String) {
        val subCommand = command.lowercase(Locale.UK).substringAfter("debug").trim()
        when (subCommand) {
            "ip" -> {
                EventBus.blockPublish(Say("My address is ${getIp()}"))
            }

            "hello" -> {
                EventBus.blockPublish(Say("Hello there!"))
            }

            "echo" -> {
                echoBackEnabled = !echoBackEnabled
                val status = if (echoBackEnabled) "enabled" else "disabled"
                EventBus.blockPublish(Say("Echo back $status"))
            }

            else -> {
                EventBus.blockPublish(Say("Debug command not found supported commands are: I.P., hello, echo i heard $subCommand"))
            }
        }
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