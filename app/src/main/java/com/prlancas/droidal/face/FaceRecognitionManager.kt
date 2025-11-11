package com.prlancas.droidal.face

import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.face.Face
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages face recognition by extracting embeddings from detected faces.
 * 
 * For now, uses a simplified geometric feature extraction based on face landmarks.
 * In production, this should use TensorFlow Lite with a FaceNet-like model for better accuracy.
 */
object FaceRecognitionManager {
    private const val TAG = "FaceRecognitionManager"
    private const val EMBEDDING_SIZE = 128 // Standard face embedding size

    // Store the most recently detected face for setName association
    private var currentFace: Face? = null
    private var currentImageRect: Rect? = null

    /**
     * Set the current face for potential association with a user name
     */
    fun setCurrentFace(face: Face, imageRect: Rect) {
        currentFace = face
        currentImageRect = imageRect
    }

    /**
     * Associate the current face with a user name
     */
    suspend fun associateCurrentFaceWithUser(userName: String): Boolean {
        val face = currentFace
        val imageRect = currentImageRect

        return if (face != null && imageRect != null) {
            storeFace(userName, face, imageRect)
            true
        } else {
            Log.w(TAG, "No current face available to associate with user: $userName")
            false
        }
    }

    /**
     * Extract a face embedding from a detected face.
     * This is a simplified version using geometric features.
     * For production, replace with TensorFlow Lite model inference.
     */
    suspend fun extractEmbedding(
        face: Face,
        imageRect: Rect
    ): FloatArray = withContext(Dispatchers.Default) {
        try {
            // Extract geometric features from face landmarks
            val features = mutableListOf<Float>()

            // Bounding box features (normalized)
            val boundingBox = face.boundingBox
            features.add((boundingBox.left - imageRect.left).toFloat() / imageRect.width())
            features.add((boundingBox.top - imageRect.top).toFloat() / imageRect.height())
            features.add(boundingBox.width().toFloat() / imageRect.width())
            features.add(boundingBox.height().toFloat() / imageRect.height())

            // Face rotation
            features.add(face.headEulerAngleY ?: 0f)
            features.add(face.headEulerAngleZ ?: 0f)

            // Left eye position (if available)
            face.leftEyeOpenProbability?.let { features.add(it) }
            face.rightEyeOpenProbability?.let { features.add(it) }
            face.smilingProbability?.let { features.add(it) }

            // Landmark-based features (if contours are enabled)
            // Note: Currently CONTOUR_MODE_NONE is set, so landmarks won't be available
            // To enable: change FaceDetectorOptions.CONTOUR_MODE_NONE to CONTOUR_MODE_ALL

            // Pad or truncate to fixed size
            val embedding = FloatArray(EMBEDDING_SIZE) { index ->
                if (index < features.size) {
                    features[index]
                } else {
                    // Use normalized geometric features for padding
                    when (index % 4) {
                        0 -> boundingBox.width().toFloat() / imageRect.width()
                        1 -> boundingBox.height().toFloat() / imageRect.height()
                        2 -> (boundingBox.centerX() - imageRect.centerX()).toFloat() / imageRect.width()
                        else -> (boundingBox.centerY() - imageRect.centerY()).toFloat() / imageRect.height()
                    }
                }
            }

            // Normalize the embedding
            normalizeEmbedding(embedding)

            Log.d(TAG, "Extracted embedding of size ${embedding.size}")
            embedding
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting embedding: ${e.message}", e)
            // Return zero embedding on error
            FloatArray(EMBEDDING_SIZE)
        }
    }

    /**
     * Normalize embedding vector to unit length
     */
    private fun normalizeEmbedding(embedding: FloatArray) {
        var sumSquares = 0f
        for (value in embedding) {
            sumSquares += value * value
        }
        val norm = kotlin.math.sqrt(sumSquares)
        if (norm > 0) {
            for (i in embedding.indices) {
                embedding[i] /= norm
            }
        }
    }

    /**
     * Store a face with a user name
     */
    suspend fun storeFace(
        userName: String,
        face: Face,
        imageRect: Rect
    ) = withContext(Dispatchers.Default) {
        try {
            val embedding = extractEmbedding(face, imageRect)
            FaceStorage.storeFace(userName, embedding)
            Log.d(TAG, "Stored face for user: $userName")
        } catch (e: Exception) {
            Log.e(TAG, "Error storing face: ${e.message}", e)
        }
    }
}

