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
            
            // NO TIMEOUT - it causes cross-thread ImageProxy close which crashes the camera driver!
            // Instead, rely on ML Kit's internal timeout and STRATEGY_KEEP_ONLY_LATEST to drop old frames
            
            // Ensure ImageProxy is closed after ML Kit processing completes
            // CRITICAL: ML Kit callbacks run on their own threads, but ImageProxy.close() MUST be
            // called from the same thread/executor. This is why we get SEGV - cross-thread native calls!
            task.addOnCompleteListener { completedTask ->
                try {
                    Log.v(TAG, "addOnCompleteListener fired for timestamp=$timestamp")
                    // ML Kit processing is complete (success or failure)
                    if (completedTask.isSuccessful && completedTask.result != null) {
                        onSuccess(
                            completedTask.result!!,
//                            graphicOverlay,
                            rect,
                            imageProxy
                        )
                        // Close ImageProxy after onSuccess completes - use safe close method
                        // since we're on ML Kit's thread pool, not CameraX executor thread
                        closeImageProxySafely(imageProxy, timestamp, "success path")
                    } else {
                        onFailure(completedTask.exception ?: Exception("Unknown error"))
                        // onFailure doesn't get imageProxy, so close it here
                        // Post close back to CameraX executor thread
                        closeImageProxySafely(imageProxy, timestamp, "failure path")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception in addOnCompleteListener: ${e.message}", e)
                    // If anything goes wrong, ensure imageProxy is closed and flag is reset
                    closeImageProxySafely(imageProxy, timestamp, "exception handler")
                    // Call onFailure to reset the isProcessing flag
                    try {
                        onFailure(e)
                    } catch (failureException: Exception) {
                        // Ignore exceptions in onFailure
                    }
                }
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

    private fun closeImageProxySafely(imageProxy: ImageProxy, timestamp: Long, context: String) {
        val executor = cameraExecutor
        if (executor != null) {
            // Post close operation back to CameraX executor thread
            executor.execute {
                try {
                    Log.v(TAG, "Closing imageProxy in $context (timestamp=$timestamp)")
                    imageProxy.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing ImageProxy: ${e.message}", e)
                }
            }
        } else {
            // Fallback: try to close on current thread (not ideal but better than crashing)
            try {
                Log.v(TAG, "Closing imageProxy in $context (timestamp=$timestamp) - no executor, closing on current thread")
                imageProxy.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing ImageProxy: ${e.message}", e)
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