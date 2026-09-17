package com.prlancas.droidal.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.display.DisplayManager
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.prlancas.droidal.brain.tools.RobotWsClient
import com.prlancas.droidal.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private var cameraDisplayId = Display.DEFAULT_DISPLAY
    private var displayListenerRegistered = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == cameraDisplayId) {
                updateTargetRotation()
            }
        }
    }

    private var lastStreamTimeMs: Long = 0L
    private val streamScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        instance = this
    }

    private val executor = Executors.newSingleThreadExecutor()

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()

                val faceAnalyzer = FaceContourDetectionProcessor()
                // Pin BaseImageAnalyzer's ImageProxy.close() onto the
                // single-thread camera executor; see the comment in
                // BaseImageAnalyzer.analyze for the full reasoning.
                faceAnalyzer.setCameraExecutor(executor)
                val compositeAnalyzer = ImageAnalysis.Analyzer { imageProxy ->
                    maybeStreamFrame(imageProxy)
                    faceAnalyzer.analyze(imageProxy)
                }
                val targetRotation = displayRotation()
                imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(targetRotation)
                    .build()
                    .also { it.setAnalyzer(executor, compositeAnalyzer) }

                imageCapture = ImageCapture.Builder()
                    .setTargetRotation(targetRotation)
                    .build()

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()

                setCameraConfig(cameraProvider, cameraSelector)
                registerDisplayListener()
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /** Stop listening for display rotation changes when the owning activity exits. */
    fun stopCamera() {
        if (displayListenerRegistered) {
            displayManager?.unregisterDisplayListener(displayListener)
            displayListenerRegistered = false
        }
    }

    /**
     * CameraX calculates [ImageProxy.imageInfo.rotationDegrees] relative to
     * each use case's target rotation. The app is sensor-landscape, so a
     * 180-degree turn stays landscape and does not recreate the activity.
     * Follow display changes explicitly so streamed JPEGs remain upright in
     * either landscape direction.
     */
    private fun registerDisplayListener() {
        if (displayListenerRegistered) return
        cameraDisplayId = context.display?.displayId ?: Display.DEFAULT_DISPLAY
        displayManager?.registerDisplayListener(displayListener, null)
        displayListenerRegistered = true
        updateTargetRotation()
    }

    private fun updateTargetRotation() {
        val targetRotation = displayRotation()
        imageAnalyzer?.targetRotation = targetRotation
        imageCapture?.targetRotation = targetRotation
    }

    private fun displayRotation(): Int = context.display?.rotation ?: Surface.ROTATION_0

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

    private fun maybeStreamFrame(imageProxy: ImageProxy) {
        val settings = SettingsRepository.get(context)
        if (!settings.cameraStreamEnabled() || !RobotWsClient.isConfigured()) {
            return
        }

        val now = System.currentTimeMillis()
        val fps = settings.cameraStreamFps().coerceIn(0.05f, 10.0f)
        val intervalMs = (1000f / fps).toLong()

        if (now - lastStreamTimeMs < intervalMs) {
            return
        }
        lastStreamTimeMs = now

        // Sample frame as Bitmap before FaceContourDetectionProcessor consumes/closes it.
        try {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val bitmap = imageProxyToBitmap(imageProxy)
            streamScope.launch {
                streamBitmap(bitmap, rotationDegrees)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to capture frame for streaming: ${e.message}")
        }
    }

    private fun streamBitmap(bitmap: Bitmap, rotationDegrees: Int) {
        try {
            val rotated = if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }

            val maxDim = STREAM_MAX_DIM
            val finalBitmap = if (rotated.width > maxDim || rotated.height > maxDim) {
                val scale = maxDim.toFloat() / maxOf(rotated.width, rotated.height)
                Bitmap.createScaledBitmap(
                    rotated,
                    (rotated.width * scale).toInt(),
                    (rotated.height * scale).toInt(),
                    true,
                )
            } else {
                rotated
            }

            val out = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, STREAM_JPEG_QUALITY, out)
            val jpegBytes = out.toByteArray()
            val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            RobotWsClient.sendCameraFrame(base64)
        } catch (e: Exception) {
            Log.w(TAG, "Error encoding/sending stream frame: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "CameraManager"
        private const val JPEG_QUALITY = 90
        private const val STREAM_JPEG_QUALITY = 75
        private const val STREAM_MAX_DIM = 640

        @Volatile
        var instance: CameraManager? = null
            private set
    }
}
