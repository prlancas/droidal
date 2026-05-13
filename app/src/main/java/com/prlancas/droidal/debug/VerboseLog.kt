package com.prlancas.droidal.debug

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.settings.SettingsRepository
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Opt-in detailed logging for LLM and vision calls.
 *
 * Gated on [SettingsRepository.verboseLoggingEnabled]. When the toggle
 * is off every helper here is a no-op so production traffic stays
 * cheap and quiet.
 *
 * When on, every helper logs under [TAG] with `Log.i` (one line per
 * call, large bodies are NOT truncated — the whole point of the
 * setting is to be able to see exactly what was sent / received).
 *
 * Companion to the existing [ConversationLog] (which lives in memory
 * and shows up in the on-device debug viewer); `VerboseLog` writes to
 * logcat where adb / Android Studio's logcat panel can capture the
 * full body. Tied to its own setting so the in-memory log can stay
 * lean while the on-disk logcat gets the deep dump.
 */
object VerboseLog {

    /** Logcat TAG every verbose helper uses, so `adb logcat -s VerboseLog`
     *  shows the full firehose without anything else in the way. */
    const val TAG = "VerboseLog"

    private val FILE_TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.UK)

    fun isEnabled(): Boolean = runCatching {
        SettingsRepository.get(Config.getContext()).verboseLoggingEnabled()
    }.getOrDefault(false)

    /**
     * Log an outbound LLM call. [provider] should identify the
     * concrete provider (e.g. "Gemini", "OpenRouter", "LiteRT-LM").
     * [prompt] is the full user / system payload — we deliberately
     * print it verbatim so a developer can copy / paste it into
     * another tool.
     */
    fun logLlmRequest(provider: String, model: String, prompt: String) {
        if (!isEnabled()) return
        Log.i(TAG, "LLM REQUEST → $provider model=$model bytes=${prompt.length}")
        Log.i(TAG, "LLM REQUEST BODY:\n$prompt")
    }

    /**
     * Log an inbound LLM reply. [response] is the assistant's
     * concatenated text reply (tool-call payloads are logged separately
     * by callers if needed).
     */
    fun logLlmResponse(provider: String, model: String, response: String) {
        if (!isEnabled()) return
        Log.i(TAG, "LLM RESPONSE ← $provider model=$model bytes=${response.length}")
        Log.i(TAG, "LLM RESPONSE BODY:\n$response")
    }

    /**
     * Log the raw HTTP / SDK payload exchanged with a cloud provider.
     * Kept separate from [logLlmRequest] so a single LLM turn that
     * actually hits the network logs both the human-readable prompt
     * and the wire-level JSON.
     */
    fun logProviderWire(provider: String, label: String, body: String) {
        if (!isEnabled()) return
        Log.i(TAG, "$provider WIRE $label bytes=${body.length}")
        Log.i(TAG, "$provider WIRE $label BODY:\n$body")
    }

    /**
     * Log an image-description call. [target] should describe where
     * the request was routed (e.g. "local LiteRT-LM (gemma-3n-E2B)",
     * "Gemini cloud", "Ollama @ host:port"). When verbose logging is
     * on and a [Context] is available the bitmap is saved to the app's
     * external files dir alongside the log line so the developer can
     * see exactly what was sent.
     */
    fun logVisionRequest(
        context: Context?,
        target: String,
        prompt: String,
        bitmap: Bitmap?,
    ): File? {
        if (!isEnabled()) return null
        val ctx = context ?: runCatching { Config.getContext() }.getOrNull()
        val savedFile = bitmap?.let { saveVisionImage(ctx, it) }
        Log.i(
            TAG,
            "VISION REQUEST → $target prompt=\"${prompt.take(120)}\"" +
                (savedFile?.let { " imagePath=${it.absolutePath}" } ?: " imagePath=<not saved>"),
        )
        return savedFile
    }

    fun logVisionResponse(target: String, description: String?) {
        if (!isEnabled()) return
        if (description.isNullOrBlank()) {
            Log.i(TAG, "VISION RESPONSE ← $target (empty/null)")
            return
        }
        Log.i(TAG, "VISION RESPONSE ← $target bytes=${description.length}")
        Log.i(TAG, "VISION RESPONSE BODY:\n$description")
    }

    /**
     * Save [bitmap] under `Context.getExternalFilesDir("vision-debug")`
     * with a timestamped JPEG name. Best-effort — failures are logged
     * and swallowed since this is purely a debug aid.
     */
    private fun saveVisionImage(context: Context?, bitmap: Bitmap): File? {
        val ctx = context ?: return null
        val dir = try {
            ctx.getExternalFilesDir("vision-debug")
                ?: File(ctx.filesDir, "vision-debug").also { it.mkdirs() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve vision-debug dir: ${e.message}")
            return null
        }
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create $dir")
            return null
        }
        val name = "vision-${FILE_TIMESTAMP_FORMAT.format(Date())}.jpg"
        val file = File(dir, name)
        return try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, out)
            }
            file
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write $file: ${e.message}")
            null
        }
    }

    private const val IMAGE_QUALITY = 90
}
