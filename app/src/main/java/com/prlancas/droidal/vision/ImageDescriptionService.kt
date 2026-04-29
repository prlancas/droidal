package com.prlancas.droidal.vision

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Abstract base class for image description services
 */
abstract class ImageDescriptionService {
    protected val TAG = this::class.simpleName ?: "ImageDescriptionService"
    
    /**
     * Describe what's in the image
     * @param bitmap The image to describe
     * @return Description of the image, or null if description failed
     */
    abstract suspend fun describeImage(bitmap: Bitmap): String?
    
    /**
     * Convert bitmap to base64 string
     */
    protected fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val imageBytes = outputStream.toByteArray()
        return Base64.encodeToString(imageBytes, Base64.NO_WRAP)
    }
}
