package com.prlancas.droidal.config

import android.content.Context
import java.util.Properties

object Config {
    private lateinit var appContext: Context
    private lateinit var secrets: Properties

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!this::appContext.isInitialized) {
            throw IllegalStateException("Config not initialized. Call Config.init(context) in your Application class.")
        }

        secrets = appContext.assets.open("keys.properties").use {
            Properties().apply { load(it) }
        }
    }

    fun key(keyName: String) = secrets.getProperty(keyName)
}
