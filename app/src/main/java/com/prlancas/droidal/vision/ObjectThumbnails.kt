package com.prlancas.droidal.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Saves and encodes small JPEG thumbnails for object landmarks.
 *
 * Each landmark keeps a cropped thumbnail (from the object's bbox when
 * available, else the whole frame) under `Context.getExternalFilesDir("object-thumbs")`,
 * mirroring [com.prlancas.droidal.debug.VerboseLog]'s vision-debug dir pattern.
 * The same JPEG is base64-encoded to push to the ROS host visualiser.
 */
object ObjectThumbnails {

    private const val TAG = "ObjectThumbnails"
    private const val DIR = "object-thumbs"
    private const val MAX_EDGE_PX = 240
    private const val JPEG_QUALITY = 80

    /**
     * Build a thumbnail bitmap for [source], cropped to [bboxNorm]
     * `[x0,y0,x1,y1]` (0..1) when present, and downscaled so its longest edge
     * is at most [MAX_EDGE_PX].
     */
    fun thumbnail(source: Bitmap, bboxNorm: List<Float>?): Bitmap {
        val cropped = bboxNorm?.let { crop(source, it) } ?: source
        val longest = maxOf(cropped.width, cropped.height)
        if (longest <= MAX_EDGE_PX) return cropped
        val scale = MAX_EDGE_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(
            cropped,
            (cropped.width * scale).toInt().coerceAtLeast(1),
            (cropped.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun crop(source: Bitmap, bbox: List<Float>): Bitmap {
        val w = source.width
        val h = source.height
        val x0 = (bbox[0].coerceIn(0f, 1f) * w).toInt()
        val y0 = (bbox[1].coerceIn(0f, 1f) * h).toInt()
        val x1 = (bbox[2].coerceIn(0f, 1f) * w).toInt()
        val y1 = (bbox[3].coerceIn(0f, 1f) * h).toInt()
        val rect = Rect(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
        val cw = (rect.width()).coerceAtLeast(1).coerceAtMost(w)
        val ch = (rect.height()).coerceAtLeast(1).coerceAtMost(h)
        if (cw <= 1 || ch <= 1) return source
        return runCatching {
            Bitmap.createBitmap(source, rect.left.coerceIn(0, w - 1), rect.top.coerceIn(0, h - 1), cw, ch)
        }.getOrDefault(source)
    }

    /** Save [thumb] as `<uuid>.jpg`, returning the absolute path or null on failure. */
    fun save(context: Context, uuid: String, thumb: Bitmap): String? {
        val dir = runCatching {
            context.getExternalFilesDir(DIR) ?: File(context.filesDir, DIR).also { it.mkdirs() }
        }.getOrNull() ?: return null
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "$uuid.jpg")
        return runCatching {
            FileOutputStream(file).use { out -> thumb.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
            file.absolutePath
        }.onFailure { Log.w(TAG, "thumb save failed: ${it.message}") }.getOrNull()
    }

    /** Base64 (NO_WRAP) JPEG for pushing to the host visualiser. */
    fun toBase64(thumb: Bitmap): String {
        val out = ByteArrayOutputStream()
        thumb.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
