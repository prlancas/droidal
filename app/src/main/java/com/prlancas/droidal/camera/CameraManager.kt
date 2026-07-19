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
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Owns the front-facing CameraX session: wires the [ImageAnalysis] use
 * case to a [FaceContourDetectionProcessor] (so the face triggers gaze /
 * `setName`-tool tracking) and exposes a single [captureImage] entry
 * point used by the "what can you see" debug command and the LLM vision
 * tool.
 *
 * There is no preview surface — Droidal renders its animated face on
 * [com.prlancas.droidal.ui.FaceCanvas] instead, so the camera runs
 * headless. The single [instance] reference lets [captureImage] be called
 * from outside an Activity (e.g. [com.prlancas.droidal.debug.DebugHandle]).
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {

    private var imageAnalyzer: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null

    init {
        instance = this
    }

    private val executor = Executors.newSingleThreadExecutor()

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()

                val analyzer = FaceContourDetectionProcessor()
                // Pin BaseImageAnalyzer's ImageProxy.close() onto the
                // single-thread camera executor; see the comment in
                // BaseImageAnalyzer.analyze for the full reasoning.
                analyzer.setCameraExecutor(executor)
                imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, analyzer) }

                imageCapture = ImageCapture.Builder().build()

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()

                setCameraConfig(cameraProvider, cameraSelector)
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun setCameraConfig(
        cameraProvider: ProcessCameraProvider?,
        cameraSelector: CameraSelector,
    ) {
        try {
            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageAnalyzer,
                imageCapture,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed", e)
        }
    }

    /**
     * Capture a single image from the camera and return it as a Bitmap.
     * @param callback receives the captured Bitmap, or `null` if capture failed.
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
            },
        )
    }

    private fun imageProxyToBitmap(imageProxy: androidx.camera.core.ImageProxy): Bitmap {
        val mediaImage = imageProxy.image
            ?: error("MediaImage is null")
        return when (val format = mediaImage.format) {
            ImageFormat.JPEG -> {
                val buffer = imageProxy.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: error("Failed to decode JPEG")
            }
            ImageFormat.YUV_420_888 -> yuv420ToBitmap(imageProxy)
            else -> error("Unsupported image format: $format")
        }
    }

    private fun yuv420ToBitmap(imageProxy: androidx.camera.core.ImageProxy): Bitmap {
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
            null,
        )

        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            Rect(0, 0, imageProxy.width, imageProxy.height),
            JPEG_QUALITY,
            out,
        )
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: error("Failed to decode YUV image")
    }

    companion object {
        private const val TAG = "CameraManager"
        private const val JPEG_QUALITY = 90

        @Volatile
        var instance: CameraManager? = null
            private set
    }
}
