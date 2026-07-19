package com.prlancas.droidal.config

import android.content.Context
import java.util.Properties

/**
 * App-wide, process-scoped config.
 *
 * Only carries secrets that genuinely *must* ship in the APK — currently
 * just the Picovoice Porcupine wake-word access key. LLM API keys (Gemini,
 * OpenRouter) are never bundled; they are configured at runtime via the
 * Settings panel and stored in
 * [com.prlancas.droidal.settings.SettingsRepository]'s encrypted prefs.
 */
object Config {
    private lateinit var appContext: Context
    private lateinit var secrets: Properties

    fun init(context: Context) {
        appContext = context.applicationContext
        secrets = appContext.assets.open("keys.properties").use {
            Properties().apply { load(it) }
        }
    }

    /**
     * Returns the configured value for [keyName] from the bundled
     * `assets/keys.properties` (wake-word key etc.). LLM keys are
     * deliberately NOT routed through here — read those from
     * [com.prlancas.droidal.settings.SettingsRepository] directly.
     */
    fun key(keyName: String): String? = secrets.getProperty(keyName)

    fun getContext(): Context {
        check(this::appContext.isInitialized) {
            "Config not initialized. Call Config.init(context) in your Application class."
        }
        return appContext
    }
}
