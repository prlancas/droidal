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
            SettingsRepository.Provider.OPENROUTER -> buildOpenRouterOrFallback(settings)
            SettingsRepository.Provider.GEMINI -> buildGemini(settings)
        }
    }

    /**
     * If the user has the LOCAL provider selected and the chosen model
     * is downloaded, kick off a background load of the LiteRT-LM engine
     * via [LiteRtLmEngineCache.prewarm]. No-op for cloud providers, no-op
     * if the model isn't downloaded yet.
     *
     * Called from `MainActivity.onResume` so the multi-second engine
     * initialisation overlaps with the wake-word idle window instead of
     * landing in the gap between "hey Droidal" and the first reply. The
     * brain-load filler in `Agent.haveConversation` still covers the
     * case where a conversation starts before the prewarm finishes.
     */
    fun prewarmIfLocal(context: Context) {
        val settings = SettingsRepository.get(context)
        if (settings.provider() != SettingsRepository.Provider.LOCAL) return
        val model = ModelCatalogLoader
            .findByName(context, settings.localModelName())
            ?.takeIf { it.isDownloaded(context) }
            ?: return
        LiteRtLmEngineCache.prewarm(context, model)
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
            buildOpenRouterOrFallback(settings)
        }
    }

    private fun buildOpenRouterOrFallback(settings: SettingsRepository): LlmProvider {
        val key = settings.openRouterKey()
        return if (!key.isNullOrBlank()) {
            OpenRouterProvider(apiKey = key, modelName = settings.openRouterModel())
        } else {
            buildGemini(settings)
        }
    }

    private fun buildGemini(settings: SettingsRepository): LlmProvider {
        val key = settings.geminiKey()
            ?: error(
                "No LLM configured. Open settings and either enter a Gemini or OpenRouter " +
                    "API key, or download a local model.",
            )
        return GeminiRestProvider(apiKey = key)
    }
}
