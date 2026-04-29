package com.prlancas.droidal.camera

import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Expression
import com.prlancas.droidal.event.events.Look
import com.prlancas.droidal.event.events.StartConversation
import com.prlancas.droidal.face.FaceRecognitionManager
import com.prlancas.droidal.face.FaceStorage
import com.prlancas.droidal.status.GlobalStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class FaceContourDetectionProcessor(
//    private val view: GraphicOverlay?
) : BaseImageAnalyzer<List<Face>>() {

    private var reportedFaces = false
    private val recognitionScope = CoroutineScope(Dispatchers.Default)
    private val isProcessing = AtomicBoolean(false)
    private var lastFaceCount = 0
    
    override fun analyze(imageProxy: ImageProxy) {

        if (!isProcessing.compareAndSet(false, true)) {
            // Another frame is already being processed, drop this one
            imageProxy.close()
            Log.v(TAG, "Dropped frame - already processing")
            return
        }
        Log.v(TAG, "Started processing frame")
        // We successfully claimed the processing slot
        try {
            super.analyze(imageProxy)
        } catch (e: Exception) {
            Log.e(TAG, "Exception in super.analyze: ${e.message}", e)
            // If super.analyze throws, reset the flag and close imageProxy
            try {
                imageProxy.close()
            } catch (closeException: Exception) {
                // Ignore
            }
            isProcessing.set(false)
            Log.v(TAG, "Reset isProcessing due to exception")
            throw e
        }
    }


    // options
    private val realTimeOpts = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
//        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
        .build()

    private val detector = FaceDetection.getClient(realTimeOpts)

//    override val graphicOverlay: GraphicOverlay
//        get() = view

    // detect
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
//        graphicOverlay: GraphicOverlay,
        rect: Rect,
        imageProxy: ImageProxy
    ) {
        try {
            // Note: ImageProxy will be closed by BaseImageAnalyzer after this method returns
            // We should NOT close it here since we're on ML Kit's thread pool, not CameraX executor
            
            // Process face detection results
            // This must complete synchronously BEFORE the finally block resets isProcessing
            val currentFaceCount = results.size
            val faceCountChanged = currentFaceCount != lastFaceCount
            lastFaceCount = currentFaceCount
            
            if (results.isNotEmpty()) {
                val face = results.first()
                val boundingBox = face.boundingBox
                
                // Only do expensive face recognition when face count changes
                if (Config.shouldLookForPeopleAndStartConversation() &&
                    !reportedFaces &&
                    faceCountChanged
                ) {
                    Log.d(TAG, "Face count changed to $currentFaceCount - performing face recognition")
                    
                    // Launch coroutine for face recognition ONLY when face count changes
                    recognitionScope.launch {
                        try {
                            val embedding = FaceRecognitionManager.extractEmbedding(face, rect)
                            val recognizedUser = FaceStorage.findMatch(embedding)
                            FaceRecognitionManager.setCurrentFace(face, rect)
                            
                            if (!GlobalStatus.isAwake) {
                                EventBus.publishAsync(
                                    StartConversation(
                                        startedByUser = false,
                                        message = "",
                                        user = recognizedUser
                                    )
                                )
                                EventBus.publishAsync(
                                    Look(
                                        ((boundingBox.centerX() - 300) / 300f) * -1,
                                        (boundingBox.centerY() - 250) / 250f,
                                        Expression.NORMAL
                                    )
                                )
                            } else {
                                EventBus.publishAsync(
                                    Look(
                                        ((boundingBox.centerX() - 300) / 300f) * -1,
                                        (boundingBox.centerY() - 250) / 250f
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in face recognition coroutine: ${e.message}", e)
                        }
                    }
                } else {
                    // Face count unchanged - just track the face with eyes, no recognition
                    if (!GlobalStatus.isAwake && faceCountChanged) {
                        // Face count changed but not doing recognition
                        if (Config.shouldLookForPeopleAndStartConversation()) {
                            EventBus.publishAsync(StartConversation(startedByUser = false, message = "", user = null))
                            }
                        EventBus.publishAsync(Look(((boundingBox.centerX() - 300) / 300f) * -1, (boundingBox.centerY() - 250) / 250f, Expression.NORMAL))
                    } else {
                        // Just track with eyes (lightweight operation every frame)
                        EventBus.publishAsync(Look(((boundingBox.centerX() - 300) / 300f) * -1, (boundingBox.centerY() - 250) / 250f))
                    }
                }
            } else {
                // No faces detected - reset if face count changed from >0 to 0
                if (faceCountChanged) {
                    Log.d(TAG, "All faces lost")
                }
            }

//        graphicOverlay.clear()
//        results.forEach {
//            val faceGraphic = FaceContourGraphic(graphicOverlay, it, rect)
//            graphicOverlay.add(faceGraphic)
//        }
//        graphicOverlay.postInvalidate()
        } finally {
            // Always reset the flag, even if an exception occurs
            isProcessing.set(false)
            Log.v(TAG, "Finished processing frame (success)")
        }
    }
    
    override fun onFailure(e: Exception) {
        try {
            Log.w(TAG, "Face Detector failed.$e")
        } finally {
            // Always reset the flag, even if logging fails
            isProcessing.set(false)
            Log.v(TAG, "Finished processing frame (failure)")
        }
    }

    companion object {
        private const val TAG = "FaceDetectorProcessor"
    }

}