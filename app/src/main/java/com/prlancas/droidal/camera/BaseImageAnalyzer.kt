package com.prlancas.droidal.camera

import android.annotation.SuppressLint
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor

/**
 * Abstract base for ML Kit-backed CameraX analyzers. Subclasses provide
 * the per-frame [detectInImage] call (e.g. face detection, OCR, object
 * detection) and the [onSuccess] / [onFailure] handlers; this base
 * handles the [ImageProxy] lifecycle and forwards completion onto the
 * camera analyzer's executor so [ImageProxy.close] runs on the
 * thread CameraX expects (see comment in [analyze]).
 */
abstract class BaseImageAnalyzer<T> : ImageAnalysis.Analyzer {

    private var cameraExecutor: Executor? = null

    fun setCameraExecutor(executor: Executor) {
        cameraExecutor = executor
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        // Capture timestamp BEFORE the callback - accessing it in callback can cause NPE
        val timestamp = try {
            imageProxy.imageInfo.timestamp
        } catch (e: Exception) {
            Log.w(TAG, "Could not read imageInfo.timestamp: ${e.message}")
            -1L
        }
        if (mediaImage == null) {
            Log.w(TAG, "mediaImage is null, closing imageProxy")
            imageProxy.close()
            runCatching { onFailure(IllegalStateException("MediaImage is null")) }
                .onFailure { Log.w(TAG, "onFailure threw: ${it.message}") }
            return
        }
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val rect = mediaImage.cropRect

        val task = detectInImage(inputImage)

        // Pin the completion listener to the camera analyzer's single-thread
        // executor (the same thread that just called analyze()). Two reasons:
        // 1. By default Play-Services Tasks fire addOnCompleteListener on the
        //    MAIN thread. At ~30fps that floods the main looper with face-
        //    detection callbacks during startup — and on the S23 we hit it
        //    while TTS, the recogniser, and the LiteRT-LM prewarm (which shares
        //    the app's EGL context for OpenCL) are also competing. The
        //    classic outcome is the camera ImageReader buffer queue
        //    starving and the input-dispatcher channel being broken
        //    (ANR) — which manifests as "the app exits before anything
        //    happens".
        // 2. imageProxy.close() should be called from the CameraX analyzer
        //    executor thread — running the listener there means we can
        //    close directly instead of doing a second cross-thread post,
        //    which the comment we removed warned could SEGV the camera
        //    driver.
        val onComplete: (Task<T>) -> Unit = { completedTask ->
            try {
                if (completedTask.isSuccessful && completedTask.result != null) {
                    onSuccess(completedTask.result!!, rect, imageProxy)
                } else {
                    onFailure(completedTask.exception ?: IllegalStateException("Unknown error"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in addOnCompleteListener: ${e.message}", e)
                runCatching { onFailure(e) }
                    .onFailure { Log.w(TAG, "onFailure threw: ${it.message}") }
            } finally {
                runCatching { imageProxy.close() }
                    .onFailure { Log.e(TAG, "Error closing ImageProxy (timestamp=$timestamp): ${it.message}", it) }
            }
        }
        val executor = cameraExecutor
        if (executor != null) {
            task.addOnCompleteListener(executor, onComplete)
        } else {
            // No executor wired up — fall back to the default Tasks
            // dispatcher rather than dropping the frame. We still
            // close the image in `finally` so buffers don't leak.
            task.addOnCompleteListener(onComplete)
        }
    }

    protected abstract fun detectInImage(image: InputImage): Task<T>

    abstract fun stop()

    protected abstract fun onSuccess(
        results: T,
        rect: Rect,
        imageProxy: ImageProxy,
    )

    protected abstract fun onFailure(e: Exception)

    companion object {
        private const val TAG = "BaseImageAnalyzer"
    }
}
