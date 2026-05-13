package com.prlancas.droidal.camera

import android.annotation.SuppressLint
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor

abstract class BaseImageAnalyzer<T> : ImageAnalysis.Analyzer {

//    abstract val graphicOverlay: GraphicOverlay

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
            -1L
        }
        Log.v(TAG, "analyze() called with timestamp=$timestamp")
        if (mediaImage != null) {
            // Create InputImage - ML Kit processes this asynchronously
            // We must keep ImageProxy open until ML Kit processing completes
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val rect = mediaImage.cropRect

            // Process the image - ML Kit will handle it asynchronously
            val task = detectInImage(inputImage)

            // Pin the completion listener to the camera analyzer's single-thread
            // executor (the same thread that just called analyze()). Two reasons:
            // 1. By default Play-Services Tasks fire addOnCompleteListener on the
            //    MAIN thread. At ~30fps that floods the main looper with face-
            //    detection callbacks during startup — and on the S23 we hit it
            //    while TTS, Porcupine, and the LiteRT-LM prewarm (which shares
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
            val executor = cameraExecutor
            val onComplete: (Task<T>) -> Unit = { completedTask ->
                try {
                    Log.v(TAG, "addOnCompleteListener fired for timestamp=$timestamp")
                    if (completedTask.isSuccessful && completedTask.result != null) {
                        onSuccess(completedTask.result!!, rect, imageProxy)
                    } else {
                        onFailure(completedTask.exception ?: Exception("Unknown error"))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception in addOnCompleteListener: ${e.message}", e)
                    try {
                        onFailure(e)
                    } catch (failureException: Exception) {
                        // Swallow — onFailure exists to reset the busy flag, not
                        // to throw. We close imageProxy below regardless.
                    }
                } finally {
                    try {
                        Log.v(TAG, "Closing imageProxy (timestamp=$timestamp)")
                        imageProxy.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing ImageProxy: ${e.message}", e)
                    }
                }
            }
            if (executor != null) {
                task.addOnCompleteListener(executor, onComplete)
            } else {
                // No executor wired up — fall back to the default Tasks
                // dispatcher rather than dropping the frame. We still
                // close the image in `finally` so buffers don't leak.
                task.addOnCompleteListener(onComplete)
            }
        } else {
            // mediaImage is null - close the ImageProxy and reset flag (if using FaceContourDetectionProcessor)
            Log.w(TAG, "mediaImage is null, closing imageProxy")
            imageProxy.close()
            // Call onFailure to reset the isProcessing flag
            try {
                onFailure(Exception("MediaImage is null"))
            } catch (e: Exception) {
                // Ignore exceptions in onFailure
            }
        }
    }

    protected abstract fun detectInImage(image: InputImage): Task<T>

    abstract fun stop()

    protected abstract fun onSuccess(
        results: T,
//        graphicOverlay: GraphicOverlay,
        rect: Rect,
        imageProxy: ImageProxy
    )

    protected abstract fun onFailure(e: Exception)
    
    companion object {
        private const val TAG = "BaseImageAnalyzer"
    }

}