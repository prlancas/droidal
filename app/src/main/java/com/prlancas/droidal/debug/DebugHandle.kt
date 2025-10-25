package com.prlancas.droidal.debug

import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Look
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
                EventBus.publishAsync(Say("My address is ${getIp()}"))
            }

            "hello" -> {
                EventBus.publishAsync(Say("Hello there!"))
            }

            "echo" -> {
                echoBackEnabled = !echoBackEnabled
                val status = if (echoBackEnabled) "enabled" else "disabled"
                EventBus.publishAsync(Say("Echo back $status"))
            }

            "look sleepy" -> {
                EventBus.publishAsync(Look(0.0f, 0.0f, com.prlancas.droidal.event.events.Expression.SLEEPY))
                EventBus.publishAsync(Say("Looking sleepy"))
            }

            "blink" -> {
                EventBus.publishAsync(Look(0.0f, 0.0f, com.prlancas.droidal.event.events.Expression.BLINK))
                EventBus.publishAsync(Say("Blinking"))
            }

            "think" -> {
                EventBus.publishAsync(Look(0.0f, 0.0f, com.prlancas.droidal.event.events.Expression.THINKING))
                EventBus.publishAsync(Say("Thinking"))
            }

            "sleep" -> {
                EventBus.publishAsync(Look(0.0f, 0.0f, com.prlancas.droidal.event.events.Expression.SLEEP))
                EventBus.publishAsync(Say("Going to sleep"))
            }

            "look normal" -> {
                EventBus.publishAsync(Look(0.0f, 0.0f, com.prlancas.droidal.event.events.Expression.NORMAL))
                EventBus.publishAsync(Say("Looking normal"))
            }

            "look cute" -> {
                EventBus.publishAsync(Look(0.0f, 0.0f, com.prlancas.droidal.event.events.Expression.CUTE))
                EventBus.publishAsync(Say("Looking cute"))
            }

            "look bloodshot" -> {
                EventBus.publishAsync(Look(0.0f, 0.0f, com.prlancas.droidal.event.events.Expression.BLOODSHOT))
                EventBus.publishAsync(Say("Looking bloodshot"))
            }

            else -> {
                EventBus.publishAsync(Say("Debug command not found. Supported commands are: ip, hello, echo, look sleepy, blink, think, sleep, look normal, look cute, look bloodshot. I heard: $subCommand"))
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