package com.prlancas.droidal.settings.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.prlancas.droidal.settings.SettingsRepository
import com.prlancas.droidal.settings.data.Model
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Status snapshot exposed to the settings UI for one model. */
data class DownloadStatus(
    val state: State,
    val percent: Int,
    val receivedBytes: Long,
    val totalBytes: Long,
    val errorMessage: String? = null,
    val authRequired: Boolean = false,
) {
    enum class State { IDLE, ENQUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
}

/** Schedules and observes [DownloadWorker]s — one per model name. */
class DownloadRepository(context: Context) {

    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val settings = SettingsRepository.get(appContext)

    fun start(model: Model) {
        val data = Data.Builder()
            .putString(DownloadWorker.KEY_URL, model.downloadUrl)
            .putString(DownloadWorker.KEY_MODEL_NAME, model.name)
            .putString(DownloadWorker.KEY_COMMIT_HASH, model.commitHash)
            .putString(DownloadWorker.KEY_FILE_NAME, model.modelFile)
            .putString(DownloadWorker.KEY_NORMALIZED_NAME, model.normalizedName())
            .putLong(DownloadWorker.KEY_TOTAL_BYTES, model.sizeInBytes)
            .putString(DownloadWorker.KEY_ACCESS_TOKEN, settings.hfAccessToken())
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(data)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag(workTagFor(model))
            .build()

        workManager.enqueueUniqueWork(
            uniqueNameFor(model),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(model: Model) {
        workManager.cancelUniqueWork(uniqueNameFor(model))
    }

    /** Stream the most recent [DownloadStatus] for the given model. */
    fun observe(model: Model): Flow<DownloadStatus> {
        return workManager.getWorkInfosForUniqueWorkFlow(uniqueNameFor(model)).map { infos ->
            val info = infos.firstOrNull()
            if (info == null) {
                if (model.isDownloaded(appContext)) {
                    DownloadStatus(
                        state = DownloadStatus.State.SUCCEEDED,
                        percent = 100,
                        receivedBytes = model.sizeInBytes,
                        totalBytes = model.sizeInBytes,
                    )
                } else {
                    DownloadStatus(DownloadStatus.State.IDLE, 0, 0, model.sizeInBytes)
                }
            } else {
                info.toStatus(model.sizeInBytes)
            }
        }
    }

    fun delete(model: Model): Boolean {
        cancel(model)
        val file = model.getFile(appContext)
        val tmp = model.getTmpFile(appContext)
        var ok = true
        if (file.exists()) ok = file.delete() && ok
        if (tmp.exists()) ok = tmp.delete() && ok
        // Also try to clean up the per-commit dir.
        file.parentFile?.takeIf { it.exists() && it.list()?.isEmpty() == true }?.delete()
        return ok
    }

    private fun WorkInfo.toStatus(totalBytesFallback: Long): DownloadStatus {
        val data = if (state.isFinished) outputData else progress
        val percent = data.getInt(DownloadWorker.KEY_PROGRESS_PERCENT, 0)
        val received = data.getLong(DownloadWorker.KEY_RECEIVED_BYTES, 0L)
        val total = data.getLong(DownloadWorker.KEY_TOTAL_BYTES, totalBytesFallback)
        val mappedState = when (state) {
            WorkInfo.State.ENQUEUED -> DownloadStatus.State.ENQUEUED
            WorkInfo.State.RUNNING -> DownloadStatus.State.RUNNING
            WorkInfo.State.SUCCEEDED -> DownloadStatus.State.SUCCEEDED
            WorkInfo.State.FAILED -> DownloadStatus.State.FAILED
            WorkInfo.State.CANCELLED -> DownloadStatus.State.CANCELLED
            WorkInfo.State.BLOCKED -> DownloadStatus.State.ENQUEUED
        }
        val error = if (state == WorkInfo.State.FAILED) {
            outputData.getString(DownloadWorker.KEY_ERROR)
        } else null
        val authRequired = error == DownloadWorker.ERROR_AUTH_REQUIRED
        return DownloadStatus(
            state = mappedState,
            percent = if (state == WorkInfo.State.SUCCEEDED) 100 else percent,
            receivedBytes = received,
            totalBytes = total,
            errorMessage = error,
            authRequired = authRequired,
        )
    }

    companion object {
        fun uniqueNameFor(model: Model): String = "droidal-download-${model.normalizedName()}"
        fun workTagFor(model: Model): String = "droidal-model-${model.normalizedName()}"

        @Volatile private var instance: DownloadRepository? = null
        fun get(context: Context): DownloadRepository = instance ?: synchronized(this) {
            instance ?: DownloadRepository(context.applicationContext).also { instance = it }
        }
    }
}
