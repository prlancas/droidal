package com.prlancas.droidal.brain.llm

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.prlancas.droidal.settings.SettingsRepository
import com.prlancas.droidal.settings.data.ModelCatalogLoader
import com.prlancas.droidal.vision.GeminiImageDescriptionService
import com.prlancas.droidal.vision.OllamaImageDescriptionService

/**
 * Routes image-description calls based on settings:
 *
 * 1. If the user has selected a local multimodal model and toggled
 *    "Use local model for image description" → run it through LiteRT-LM
 *    (sharing the [LiteRtLmEngineCache] engine with the chat agent).
 * 2. Otherwise → fall back to the existing cloud Gemini service (or Ollama
 *    when no Gemini key is available).
 */
class ImageDescriber(private val appContext: Context) {

    private val settings = SettingsRepository.get(appContext)

    suspend fun describe(bitmap: Bitmap, prompt: String = DEFAULT_PROMPT): String? {
        if (settings.useLocalForVision()
            && settings.provider() == SettingsRepository.Provider.LOCAL
        ) {
            val name = settings.localModelName()
            val model = ModelCatalogLoader.findByName(appContext, name)
            if (model != null && model.isDownloaded(appContext) && model.llmSupportImage) {
                try {
                    val provider = LiteRtLmProvider(
                        appContext = appContext,
                        model = model,
                        useLocalForVision = true,
                    )
                    val text = provider.describeImage(bitmap, prompt)
                    if (!text.isNullOrBlank()) return text
                    Log.w(TAG, "Local model returned empty description; falling back to cloud")
                } catch (e: Exception) {
                    Log.e(TAG, "Local image description failed: ${e.message}", e)
                }
            }
        }

        val geminiKey = settings.geminiKey()
        return if (!geminiKey.isNullOrBlank()) {
            GeminiImageDescriptionService().describeImage(bitmap)
        } else {
            OllamaImageDescriptionService().describeImage(bitmap)
        }
    }

    companion object {
        private const val TAG = "ImageDescriber"
        const val DEFAULT_PROMPT = "Describe what you see in this image in detail."
    }
}
