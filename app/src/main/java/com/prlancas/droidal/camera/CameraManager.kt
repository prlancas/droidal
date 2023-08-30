package com.prlancas.droidal.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors


class CameraManager(
    private val context: Context,
//    private val finderView: PreviewView?,
    private val lifecycleOwner: LifecycleOwner,
//    private val graphicOverlay: GraphicOverlay?
){

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null

    private fun selectAnalyzer(): ImageAnalysis.Analyzer {
//        return when (analyzerVisionType) {
//            VisionType.Object -> ObjectDetectionProcessor(graphicOverlay)
//            VisionType.OCR -> TextRecognitionProcessor(graphicOverlay)
//            VisionType.Face -> FaceContourDetectionProcessor(graphicOverlay)
//            VisionType.Barcode -> BarcodeScannerProcessor(graphicOverlay)
//        }
        return FaceContourDetectionProcessor()
    }

    private val executor = Executors.newSingleThreadExecutor()

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                val cameraProvider = cameraProviderFuture.get()
                preview= Preview.Builder().build()

                // set Analyzer
                imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(executor, selectAnalyzer())
                    }

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()

                // zoom
//                setUpPinchToZoom()
                setCameraConfig(cameraProvider, cameraSelector)

            }, ContextCompat.getMainExecutor(context)
        )
    }

    private fun setCameraConfig(
        cameraProvider: ProcessCameraProvider?,
        cameraSelector: CameraSelector
    ) {
        try {
            cameraProvider?.unbindAll()
            val camera = cameraProvider?.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
//                null, //preview,
                imageAnalyzer
            )
//            preview?.setSurfaceProvider(finderView.createSurfaceProvider())
        } catch (e: Exception) {
            println("Use case binding failed")
            e.printStackTrace()
        }
    }

}