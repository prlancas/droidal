package com.prlancas.droidal.brain.llm

import android.content.Context
import com.prlancas.droidal.settings.SettingsRepository
import com.prlancas.droidal.settings.data.ModelCatalogLoader

/**
 * Resolves the currently-configured [LlmProvider] based on what the user
 * has picked in Settings, with sensible fallbacks if the chosen provider
 * isn't actually usable (e.g. local model not yet downloaded, API key
 * missing).
 */
object LlmProviderFactory {

    fun current(context: Context): LlmProvider {
        val settings = SettingsRepository.get(context)
        return when (settings.provider()) {
            SettingsRepository.Provider.LOCAL -> buildLocalOrFallback(context, settings)
            SettingsRepository.Provider.OPENROUTER -> buildOpenRouterOrFallback(context, settings)
            SettingsRepository.Provider.GEMINI -> buildGemini(context, settings)
        }
    }

    private fun buildLocalOrFallback(
        context: Context,
        settings: SettingsRepository,
    ): LlmProvider {
        val model = ModelCatalogLoader
            .findByName(context, settings.localModelName())
            ?.takeIf { it.isDownloaded(context) }
        return if (model != null) {
            LiteRtLmProvider(
                appContext = context.applicationContext,
                model = model,
                useLocalForVision = settings.useLocalForVision(),
            )
        } else {
            // No downloaded local model yet — prefer OpenRouter if keyed,
            // else Gemini.
            buildOpenRouterOrFallback(context, settings)
        }
    }

    private fun buildOpenRouterOrFallback(
        context: Context,
        settings: SettingsRepository,
    ): LlmProvider {
        val key = settings.openRouterKey()
        return if (!key.isNullOrBlank()) {
            OpenRouterProvider(apiKey = key, modelName = settings.openRouterModel())
        } else {
            buildGemini(context, settings)
        }
    }

    private fun buildGemini(
        context: Context,
        settings: SettingsRepository,
    ): LlmProvider {
        val key = settings.geminiKey()
            ?: throw IllegalStateException(
                "No LLM configured. Open settings and either enter a Gemini or OpenRouter API key, or download a local model.",
            )
        return GeminiRestProvider(apiKey = key)
    }
}
