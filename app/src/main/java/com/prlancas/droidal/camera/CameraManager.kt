package com.prlancas.droidal.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors


class CameraManager(
    private val context: Context,
//    private val finderView: PreviewView?,
    private val lifecycleOwner: LifecycleOwner,
//    private val graphicOverlay: GraphicOverlay?
){

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null

    init {
        instance = this
    }

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
                cameraProvider = cameraProviderFuture.get()
                preview= Preview.Builder().build()

                // set Analyzer
                val analyzer = selectAnalyzer()
                // Set the executor so BaseImageAnalyzer can post ImageProxy.close() back to it
                if (analyzer is BaseImageAnalyzer<*>) {
                    analyzer.setCameraExecutor(executor)
                }
                imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(executor, analyzer)
                    }

                // Add ImageCapture for single frame capture
                imageCapture = ImageCapture.Builder()
                    .build()

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
                imageAnalyzer,
                imageCapture
            )
//            preview?.setSurfaceProvider(finderView.createSurfaceProvider())
        } catch (e: Exception) {
            println("Use case binding failed")
            e.printStackTrace()
        }
    }

    /**
     * Capture a single image from the camera and return it as a Bitmap
     * @param callback Function to receive the captured Bitmap, or null if capture failed
     */
    fun captureImage(callback: (Bitmap?) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            Log.e(TAG, "ImageCapture not initialized")
            callback(null)
            return
        }

        capture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                    try {
                        val bitmap = imageProxyToBitmap(image)
                        callback(bitmap)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error converting ImageProxy to Bitmap: ${e.message}", e)
                        callback(null)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Image capture failed: ${exception.message}", exception)
                    callback(null)
                }
            }
        )
    }

    private fun imageProxyToBitmap(imageProxy: androidx.camera.core.ImageProxy): Bitmap {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            throw IllegalStateException("MediaImage is null")
        }
        
        val format = mediaImage.format
        return when (format) {
            ImageFormat.JPEG -> {
                // JPEG format - decode directly
                val buffer = imageProxy.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IllegalStateException("Failed to decode JPEG")
            }
            ImageFormat.YUV_420_888 -> {
                // Convert YUV_420_888 to Bitmap
                val yBuffer = imageProxy.planes[0].buffer
                val uBuffer = imageProxy.planes[1].buffer
                val vBuffer = imageProxy.planes[2].buffer
                
                val ySize = yBuffer.remaining()
                val uSize = uBuffer.remaining()
                val vSize = vBuffer.remaining()
                
                val nv21 = ByteArray(ySize + uSize + vSize)
                
                yBuffer.get(nv21, 0, ySize)
                vBuffer.get(nv21, ySize, vSize)
                uBuffer.get(nv21, ySize + vSize, uSize)
                
                val yuvImage = YuvImage(
                    nv21,
                    ImageFormat.NV21,
                    imageProxy.width,
                    imageProxy.height,
                    null
                )
                
                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(
                    Rect(0, 0, imageProxy.width, imageProxy.height),
                    90,
                    out
                )
                val imageBytes = out.toByteArray()
                
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    ?: throw IllegalStateException("Failed to decode YUV image")
            }
            else -> {
                throw IllegalStateException("Unsupported image format: $format")
            }
        }
    }

    companion object {
        private const val TAG = "CameraManager"
        @Volatile
        var instance: CameraManager? = null
            private set
    }

}