package com.prlancas.droidal.brain.llm

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.prlancas.droidal.debug.VerboseLog
import com.prlancas.droidal.settings.SettingsRepository
import com.prlancas.droidal.settings.data.ModelCatalogLoader
import com.prlancas.droidal.vision.GeminiImageDescriptionService
import com.prlancas.droidal.vision.OllamaImageDescriptionService
import com.prlancas.droidal.vision.VisionObject

/**
 * Routes image-description calls based on [SettingsRepository.VisionProvider]:
 *
 * 1. LOCAL: Use only the on-device LiteRT-LM engine. Errors if the active
 *    model doesn't support vision or isn't downloaded.
 * 2. GEMINI: Use only the cloud Gemini API. Errors if no key is set.
 * 3. OLLAMA: Use only the local network Ollama instance.
 * 4. AUTO: Default. Tries LOCAL if a multimodal model is active and
 *    downloaded, otherwise falls back to GEMINI, and finally OLLAMA.
 */
class ImageDescriber(private val appContext: Context) {

    private val settings = SettingsRepository.get(appContext)

    /**
     * Capture the scene as a structured list of [VisionObject]s (canonical
     * noun + aliases + optional bbox) instead of a paragraph. Uses the shared
     * routing in [describe] with [VisionObject.EXTRACTION_PROMPT] and JSON
     * mode, then parses the reply. Empty list means "nothing usable seen".
     */
    suspend fun describeObjects(bitmap: Bitmap): List<VisionObject> {
        val raw = describe(bitmap, VisionObject.EXTRACTION_PROMPT, jsonMode = true)
        val objects = VisionObject.parseList(raw)
        Log.i(TAG, "describeObjects -> ${objects.size} objects")
        return objects
    }

    suspend fun describe(
        bitmap: Bitmap,
        prompt: String = DEFAULT_PROMPT,
        jsonMode: Boolean = false,
    ): String? {
        val visionProvider = settings.visionProvider()
        Log.i(TAG, "Routing image description with provider: $visionProvider")

        return when (visionProvider) {
            SettingsRepository.VisionProvider.LOCAL -> {
                describeLocal(bitmap, prompt) ?: "Error: Local vision failed. Ensure a multimodal model is active and downloaded."
            }
            SettingsRepository.VisionProvider.GEMINI -> {
                describeGemini(bitmap, prompt, jsonMode) ?: "Error: Gemini vision failed. Check API key and connection."
            }
            SettingsRepository.VisionProvider.OLLAMA -> {
                describeOllama(bitmap, prompt, jsonMode) ?: "Error: Ollama vision failed."
            }
            SettingsRepository.VisionProvider.AUTO -> {
                describeLocal(bitmap, prompt)
                    ?: describeGemini(bitmap, prompt, jsonMode)
                    ?: describeOllama(bitmap, prompt, jsonMode)
            }
        }
    }

    private suspend fun describeLocal(bitmap: Bitmap, prompt: String): String? {
        val name = settings.localModelName()
        val model = ModelCatalogLoader.findByName(appContext, name)
        if (model != null && model.isDownloaded(appContext) && model.llmSupportImage) {
            val target = "local LiteRT-LM (${model.name})"
            Log.i(TAG, "Trying local image description: $target")
            VerboseLog.logVisionRequest(appContext, target, prompt, bitmap)
            return try {
                val provider = LiteRtLmProvider(
                    appContext = appContext,
                    model = model,
                    useLocalForVision = true,
                )
                val text = provider.describeImage(bitmap, prompt)
                VerboseLog.logVisionResponse(target, text)
                text
            } catch (e: Exception) {
                Log.e(TAG, "Local vision failed: ${e.message}", e)
                null
            }
        }
        Log.w(TAG, "Local vision unavailable (model null, not downloaded, or not multimodal)")
        return null
    }

    private suspend fun describeGemini(bitmap: Bitmap, prompt: String, jsonMode: Boolean): String? {
        val key = settings.geminiKey()
        if (key.isNullOrBlank()) return null

        val target = "cloud Gemini"
        Log.i(TAG, "Trying Gemini vision: $target")
        VerboseLog.logVisionRequest(appContext, target, prompt, bitmap)
        return try {
            val text = GeminiImageDescriptionService().describeImage(bitmap, prompt, jsonMode)
            VerboseLog.logVisionResponse(target, text)
            text
        } catch (e: Exception) {
            Log.e(TAG, "Gemini vision failed: ${e.message}", e)
            null
        }
    }

    private suspend fun describeOllama(bitmap: Bitmap, prompt: String, jsonMode: Boolean): String? {
        val target = "Ollama (LAN)"
        Log.i(TAG, "Trying Ollama vision: $target")
        VerboseLog.logVisionRequest(appContext, target, prompt, bitmap)
        return try {
            val text = OllamaImageDescriptionService().describeImage(bitmap, prompt, jsonMode)
            VerboseLog.logVisionResponse(target, text)
            text
        } catch (e: Exception) {
            Log.e(TAG, "Ollama vision failed: ${e.message}", e)
            null
        }
    }

    companion object {
        private const val TAG = "ImageDescriber"
        const val DEFAULT_PROMPT = "Describe what you see in this image in detail."
    }
}
