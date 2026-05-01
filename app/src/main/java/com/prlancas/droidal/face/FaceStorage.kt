package com.prlancas.droidal.face

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.prlancas.droidal.config.Config
import kotlin.math.sqrt

/**
 * Stores and retrieves face embeddings associated with user names.
 * Uses SharedPreferences for simple persistence.
 */
object FaceStorage {
    private const val PREFS_NAME = "face_storage"
    private const val KEY_FACES = "stored_faces"
    private const val TAG = "FaceStorage"
    
    private val gson = Gson()
    private val threshold = 0.6f // Cosine similarity threshold for face matching
    
    data class FaceRecord(
        val userName: String,
        val embedding: FloatArray,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as FaceRecord
            return userName == other.userName && embedding.contentEquals(other.embedding)
        }
        
        override fun hashCode(): Int {
            var result = userName.hashCode()
            result = 31 * result + embedding.contentHashCode()
            return result
        }
    }
    
    private fun getSharedPrefs(): SharedPreferences {
        val context = Config.getContext()
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Store a face embedding with a user name
     */
    fun storeFace(userName: String, embedding: FloatArray) {
        try {
            val records = loadAllFaces().toMutableList()
            
            // Remove existing records for this user (update)
            records.removeAll { it.userName == userName }
            
            // Add new record
            records.add(FaceRecord(userName, embedding))
            
            // Save to SharedPreferences
            val json = gson.toJson(records.map { 
                mapOf(
                    "userName" to it.userName,
                    "embedding" to it.embedding.toList(),
                    "timestamp" to it.timestamp
                )
            })
            
            getSharedPrefs().edit().putString(KEY_FACES, json).apply()
            Log.d(TAG, "Stored face for user: $userName")
        } catch (e: Exception) {
            Log.e(TAG, "Error storing face: ${e.message}", e)
        }
    }
    
    /**
     * Find the best matching user for a given face embedding
     * @return User name if match found above threshold, null otherwise
     */
    fun findMatch(embedding: FloatArray): String? {
        val records = loadAllFaces()
        if (records.isEmpty()) return null
        
        var bestMatch: FaceRecord? = null
        var bestSimilarity = 0f
        
        for (record in records) {
            val similarity = cosineSimilarity(embedding, record.embedding)
            if (similarity > bestSimilarity && similarity >= threshold) {
                bestSimilarity = similarity
                bestMatch = record
            }
        }
        
        return bestMatch?.userName?.takeIf { bestSimilarity >= threshold }
    }
    
    /**
     * Load all stored face records
     */
    private fun loadAllFaces(): List<FaceRecord> {
        return try {
            val json = getSharedPrefs().getString(KEY_FACES, null) ?: return emptyList()
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val data: List<Map<String, Any>> = gson.fromJson(json, type)
            
            data.map {
                val userName = it["userName"] as String
                @Suppress("UNCHECKED_CAST")
                val embeddingList = it["embedding"] as List<Double>
                val embedding = embeddingList.map { it.toFloat() }.toFloatArray()
                val timestamp = (it["timestamp"] as? Double)?.toLong() ?: System.currentTimeMillis()
                FaceRecord(userName, embedding, timestamp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading faces: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Calculate cosine similarity between two embeddings
     */
    private fun cosineSimilarity(embedding1: FloatArray, embedding2: FloatArray): Float {
        if (embedding1.size != embedding2.size) {
            Log.w(TAG, "Embedding size mismatch: ${embedding1.size} vs ${embedding2.size}")
            return 0f
        }
        
        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f
        
        for (i in embedding1.indices) {
            dotProduct += embedding1[i] * embedding2[i]
            norm1 += embedding1[i] * embedding1[i]
            norm2 += embedding2[i] * embedding2[i]
        }
        
        val denominator = sqrt(norm1) * sqrt(norm2)
        return if (denominator > 0) dotProduct / denominator else 0f
    }
    
    /**
     * Get all stored user names
     */
    fun getAllUsers(): List<String> {
        return loadAllFaces().map { it.userName }.distinct()
    }
    
    /**
     * Clear all stored faces
     */
    fun clearAll() {
        getSharedPrefs().edit().remove(KEY_FACES).apply()
        Log.d(TAG, "Cleared all stored faces")
    }

    /**
     * Forget every embedding associated with [userName]. Used by the
     * "Remove user" action in the learning manager — wiping just the
     * markdown / DB rows isn't enough because [getAllUsers] (and hence
     * [com.prlancas.droidal.memory.learning.LearningStore.listUsers])
     * would still resurface the user name from face storage.
     *
     * Match is case-sensitive on the stored userName (face records are
     * keyed on the display name passed to [storeFace]); pass the same
     * value [getAllUsers] returns for safe deletion.
     */
    fun removeUser(userName: String) {
        val records = loadAllFaces()
        val remaining = records.filter { it.userName != userName }
        if (remaining.size == records.size) return
        persist(remaining)
        Log.d(TAG, "Removed face records for user: $userName")
    }

    /**
     * Re-key every embedding currently filed under [oldName] so it
     * shows up as [newName] going forward. Used by the rename flow in
     * the learning manager. No-op if [oldName] has no records.
     */
    fun renameUser(oldName: String, newName: String) {
        if (oldName == newName) return
        val records = loadAllFaces()
        val rewritten = records.map { rec ->
            if (rec.userName == oldName) rec.copy(userName = newName) else rec
        }
        if (rewritten == records) return
        persist(rewritten)
        Log.d(TAG, "Renamed face records: $oldName -> $newName")
    }

    private fun persist(records: List<FaceRecord>) {
        val json = gson.toJson(records.map {
            mapOf(
                "userName" to it.userName,
                "embedding" to it.embedding.toList(),
                "timestamp" to it.timestamp,
            )
        })
        getSharedPrefs().edit().putString(KEY_FACES, json).apply()
    }
}

