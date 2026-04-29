package com.prlancas.droidal.settings.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.prlancas.droidal.MainActivity
import com.prlancas.droidal.settings.data.Model
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pared-down port of Google AI Edge Gallery's `DownloadWorker`.
 *
 * Streams the model file with `HttpURLConnection`, supports resume via the
 * `Range:` header, writes to a `*.droidaltmp` partial file, then renames to
 * the final name on success. Posts a foreground notification with progress
 * so Android keeps the worker alive on Android 13+ (POST_NOTIFICATIONS).
 */
class DownloadWorker(
    private val appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val notificationId: Int = id.hashCode()

    init {
        if (!channelCreated) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Model Downloading",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Notifications for model downloading" }
            notificationManager.createNotificationChannel(channel)
            channelCreated = true
        }
    }

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL)
        val modelName = inputData.getString(KEY_MODEL_NAME) ?: "Model"
        val commitHash = inputData.getString(KEY_COMMIT_HASH)
        val fileName = inputData.getString(KEY_FILE_NAME)
        val normalizedName = inputData.getString(KEY_NORMALIZED_NAME)
        val totalBytes = inputData.getLong(KEY_TOTAL_BYTES, 0L)
        val accessToken = inputData.getString(KEY_ACCESS_TOKEN)

        if (url == null || fileName == null || commitHash == null || normalizedName == null) {
            return Result.failure(
                Data.Builder().putString(KEY_ERROR, "Missing required input").build(),
            )
        }

        return withContext(Dispatchers.IO) {
            try {
                setForeground(createForegroundInfo(0, modelName))

                val outputDir = File(
                    appContext.getExternalFilesDir(null),
                    normalizedName + File.separator + commitHash,
                )
                if (!outputDir.exists()) outputDir.mkdirs()

                val tmpFile = File(outputDir, "$fileName.${Model.TMP_EXT}")
                val finalFile = File(outputDir, fileName)

                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    if (!accessToken.isNullOrBlank()) {
                        setRequestProperty("Authorization", "Bearer $accessToken")
                    }
                    if (tmpFile.length() > 0) {
                        setRequestProperty("Range", "bytes=${tmpFile.length()}-")
                        setRequestProperty("Accept-Encoding", "identity")
                    }
                    connectTimeout = 30_000
                    readTimeout = 60_000
                }

                connection.connect()
                val code = connection.responseCode
                Log.d(TAG, "Response code for $fileName: $code")

                if (code == HttpURLConnection.HTTP_FORBIDDEN || code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    return@withContext Result.failure(
                        Data.Builder()
                            .putString(KEY_ERROR, ERROR_AUTH_REQUIRED)
                            .putInt(KEY_RESPONSE_CODE, code)
                            .build(),
                    )
                }

                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    return@withContext Result.failure(
                        Data.Builder()
                            .putString(KEY_ERROR, "HTTP error code: $code")
                            .putInt(KEY_RESPONSE_CODE, code)
                            .build(),
                    )
                }

                var downloadedBytes = 0L
                connection.getHeaderField("Content-Range")?.let { range ->
                    val rangeStart =
                        range.substringAfter("bytes ").substringBefore("-").toLongOrNull()
                    if (rangeStart != null) {
                        downloadedBytes = rangeStart
                    }
                }

                connection.inputStream.use { input ->
                    FileOutputStream(tmpFile, /* append = */ true).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var lastProgressTs = 0L
                        var deltaBytes = 0L
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            deltaBytes += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastProgressTs > 200) {
                                lastProgressTs = now
                                val percent = if (totalBytes > 0L) {
                                    (downloadedBytes * 100 / totalBytes).toInt()
                                } else 0
                                setProgress(
                                    Data.Builder()
                                        .putLong(KEY_RECEIVED_BYTES, downloadedBytes)
                                        .putLong(KEY_TOTAL_BYTES, totalBytes)
                                        .putInt(KEY_PROGRESS_PERCENT, percent)
                                        .build(),
                                )
                                setForeground(createForegroundInfo(percent, modelName))
                                deltaBytes = 0L
                            }
                        }
                    }
                }

                if (finalFile.exists()) finalFile.delete()
                if (!tmpFile.renameTo(finalFile)) {
                    return@withContext Result.failure(
                        Data.Builder().putString(KEY_ERROR, "Failed to finalize download").build(),
                    )
                }

                Result.success(
                    Data.Builder()
                        .putLong(KEY_RECEIVED_BYTES, downloadedBytes)
                        .putLong(KEY_TOTAL_BYTES, totalBytes)
                        .putInt(KEY_PROGRESS_PERCENT, 100)
                        .build(),
                )
            } catch (e: IOException) {
                Log.e(TAG, "Download failed: ${e.message}", e)
                Result.failure(
                    Data.Builder().putString(KEY_ERROR, e.message ?: "I/O error").build(),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}", e)
                Result.failure(
                    Data.Builder().putString(KEY_ERROR, e.message ?: "Unknown error").build(),
                )
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo(0)

    private fun createForegroundInfo(progress: Int, modelName: String? = null): ForegroundInfo {
        val title = if (modelName != null) "Downloading \"$modelName\"" else "Downloading model"
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Downloading: $progress%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .setContentIntent(pendingIntent)
            .build()
        return ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        private const val TAG = "DroidalDownloadWorker"
        private const val CHANNEL_ID = "droidal_model_download"
        private var channelCreated = false

        const val KEY_URL = "url"
        const val KEY_MODEL_NAME = "model_name"
        const val KEY_COMMIT_HASH = "commit_hash"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_NORMALIZED_NAME = "normalized_name"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_RECEIVED_BYTES = "received_bytes"
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_ERROR = "error"
        const val KEY_RESPONSE_CODE = "response_code"

        const val ERROR_AUTH_REQUIRED = "auth_required"
    }
}
