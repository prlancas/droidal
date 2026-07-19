package com.prlancas.droidal.camera

import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Look
import com.prlancas.droidal.face.FaceRecognitionManager
import com.prlancas.droidal.status.GlobalStatus
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX analyzer that runs ML Kit face detection on each frame.
 *
 * Two responsibilities, in order of priority:
 *  1. Drive Droidal's eye gaze toward whatever face is in view via [Look]
 *     events — every frame, cheaply.
 *  2. Track the most-recently-seen face on [FaceRecognitionManager] so
 *     the LLM `setName` tool has something to associate with the spoken
 *     name when the user identifies themselves.
 *
 * Frames are dropped while a previous one is still in flight (the
 * [isProcessing] CAS) so a slow detection cycle can't queue up old
 * frames behind it.
 */
class FaceContourDetectionProcessor : BaseImageAnalyzer<List<Face>>() {

    private val isProcessing = AtomicBoolean(false)
    private var lastFaceCount = 0

    override fun analyze(imageProxy: ImageProxy) {
        if (!isProcessing.compareAndSet(false, true)) {
            // Another frame is already being processed, drop this one
            imageProxy.close()
            return
        }
        try {
            super.analyze(imageProxy)
        } catch (e: Exception) {
            Log.e(TAG, "Exception in super.analyze: ${e.message}", e)
            runCatching { imageProxy.close() }
            isProcessing.set(false)
            throw e
        }
    }

    private val realTimeOpts = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
        .build()

    private val detector = FaceDetection.getClient(realTimeOpts)

    override fun detectInImage(image: InputImage): Task<List<Face>> {
        return detector.process(image)
    }

    override fun stop() {
        try {
            detector.close()
        } catch (e: IOException) {
            Log.e(TAG, "Exception thrown while trying to close Face Detector: $e")
        }
    }

    override fun onSuccess(
        results: List<Face>,
        rect: Rect,
        imageProxy: ImageProxy,
    ) {
        try {
            val currentFaceCount = results.size
            val faceCountChanged = currentFaceCount != lastFaceCount
            lastFaceCount = currentFaceCount

            if (results.isNotEmpty()) {
                // Cheap "is the user still in front of Droidal?" signal
                // for ConversationListenPolicy. Updated every frame a
                // face is visible so even a quiet user (thinking, mid-
                // sentence) keeps the patient listen budget alive.
                GlobalStatus.lastFaceSeenAtMs = System.currentTimeMillis()
                val face = results.first()
                val boundingBox = face.boundingBox

                // Stash the most-recent face so the LLM `setName` tool
                // can associate the spoken name with whatever the camera
                // is currently looking at.
                FaceRecognitionManager.setCurrentFace(face, rect)

                EventBus.publishAsync(
                    Look(
                        ((boundingBox.centerX() - 300) / 300f) * -1,
                        (boundingBox.centerY() - 250) / 250f,
                    ),
                )
            } else if (faceCountChanged) {
                Log.d(TAG, "All faces lost")
            }
        } finally {
            isProcessing.set(false)
        }
    }

    override fun onFailure(e: Exception) {
        try {
            Log.w(TAG, "Face Detector failed.$e")
        } finally {
            isProcessing.set(false)
        }
    }

    companion object {
        private const val TAG = "FaceDetectorProcessor"
    }
}
