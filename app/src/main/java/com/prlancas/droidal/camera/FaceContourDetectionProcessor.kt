package com.prlancas.droidal.camera

import android.graphics.Rect
import android.util.Log
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
import com.prlancas.droidal.status.GlobalStatus
import java.io.IOException

class FaceContourDetectionProcessor(
//    private val view: GraphicOverlay?
) : BaseImageAnalyzer<List<Face>>() {

    private var reportedFaces = false

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
        if (Config.shouldLookForPeopleAndStartConversation() &&
            results.isNotEmpty() &&
            !reportedFaces
        ) {
            val boundingBox = results.first().boundingBox
//            println("${boundingBox.centerX()}:${boundingBox.centerY()}")
            if (!GlobalStatus.isAwake) {
                //TODO work out the user from their face - for now, just null
                EventBus.publishAsync(StartConversation(startedByUser = false, message = "", user = null))
                EventBus.publishAsync(Look(((boundingBox.centerX() - 300) / 300f) * -1, (boundingBox.centerY() - 250) / 250f, Expression.NORMAL))
            } else {
                EventBus.publishAsync(Look(((boundingBox.centerX() - 300) / 300f) * -1, (boundingBox.centerY() - 250) / 250f))
            }

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