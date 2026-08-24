package com.prlancas.droidal.vision

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Abstract base class for image description services.
 *
 * Concrete subclasses send [bitmap] to a remote (Gemini, Ollama) or
 * on-device LLM and return the textual description (or `null` on
 * failure). [bitmapToBase64] is the shared helper for building the
 * inline-image envelope that both Gemini and Ollama expect.
 */
abstract class ImageDescriptionService {

    /** Logcat tag for the concrete subclass — used by every implementation
     *  for consistent filtering. */
    protected val logTag: String = this::class.simpleName ?: "ImageDescriptionService"

    /**
     * Describe / analyse the image with a caller-supplied prompt.
     *
     * @param bitmap the image to send.
     * @param prompt what to ask the model (free-form description, or the
     *   structured object-extraction prompt from
     *   [com.prlancas.droidal.vision.VisionObject.EXTRACTION_PROMPT]).
     * @param jsonMode when true, ask the backend to emit JSON only (Gemini
     *   `responseMimeType`, Ollama `format:"json"`) so structured extraction
     *   parses cleanly. Ignored by backends that don't support it.
     * @return the model's reply text, or `null` on failure.
     */
    abstract suspend fun describeImage(
        bitmap: Bitmap,
        prompt: String,
        jsonMode: Boolean,
    ): String?

    /** Convert a bitmap to a base64-encoded JPEG string. */
    protected fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        val imageBytes = outputStream.toByteArray()
        return Base64.encodeToString(imageBytes, Base64.NO_WRAP)
    }

    private companion object {
        private const val JPEG_QUALITY = 85
    }
}
