package com.prlancas.droidal.wake

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import android.content.Context
import android.util.Log
import com.prlancas.droidal.MainActivity
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import java.util.Properties

class WakeWordDetection(val mainActivity: MainActivity) {

    private lateinit var porcupineManager: PorcupineManager

    fun startWakeWordDetection(context: Context ) {
        val props  = context.assets.open("keys.properties").use {
            Properties().apply { load(it) }
        }
        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(props.getProperty("porcupine_key"))
                .setKeyword(Porcupine.BuiltInKeyword.TERMINATOR)
                .setSensitivity(0.7f)
                .build(
                    mainActivity.applicationContext
                ) {
                    EventBus.blockPublish(Say("Yes master"))
                }
            porcupineManager.start()
        } catch (e: PorcupineException) {
            Log.e("PORCUPINE_SERVICE", e.toString())
        }
    }
}