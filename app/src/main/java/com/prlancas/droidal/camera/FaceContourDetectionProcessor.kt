package com.prlancas.droidal.camera

import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Look
import java.io.IOException

class FaceContourDetectionProcessor(
//    private val view: GraphicOverlay?
) : BaseImageAnalyzer<List<Face>>() {

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
        rect: Rect
    ) {
//        println("Found ${results.size} faces")
        results.forEach {
            val boundingBox = it.boundingBox
//            X 0 - 600
//            Y 0 - 500
            println("${boundingBox.centerX()}:${boundingBox.centerY()}")
            EventBus.blockPublish(Look(((boundingBox.centerX() - 300) / 300f) * -1, (boundingBox.centerY() - 250) / 250f))
        }
//        graphicOverlay.clear()
//        results.forEach {
//            val faceGraphic = FaceContourGraphic(graphicOverlay, it, rect)
//            graphicOverlay.add(faceGraphic)
//        }
//        graphicOverlay.postInvalidate()
    }

    override fun onFailure(e: Exception) {
        Log.w(TAG, "Face Detector failed.$e")
    }

    companion object {
        private const val TAG = "FaceDetectorProcessor"
    }

}