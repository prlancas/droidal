package com.prlancas.droidal.memory.learning.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.prlancas.droidal.MainActivity
import com.prlancas.droidal.R

/**
 * Shared low-importance notification channel + [ForegroundInfo] factory used
 * by Droidal's background curator workers (news scouting, reflection, etc.).
 *
 * Promoting these workers to foreground services is what lets them keep
 * running while the phone is locked or in Doze: WorkManager's normal
 * scheduler will defer "ordinary" periodic work behind battery-saving
 * windows, but a foreground service worker is treated like any other
 * user-visible service. The notification is intentionally `IMPORTANCE_MIN`
 * so it slides into the bottom of the shade without buzzing or chiming.
 *
 * On Android 14+ every foreground service must declare a type; the
 * `dataSync` type matches what `androidx.work.impl.foreground.SystemForegroundService`
 * is merged with in `AndroidManifest.xml`.
 */
internal object BackgroundWorkNotifications {

    private const val CHANNEL_ID = "droidal_background_curators"
    private const val CHANNEL_NAME = "Background curators"
    private const val CHANNEL_DESC =
        "Quiet ongoing notifications shown while Droidal scouts news " +
            "or reflects on past conversations in the background."

    @Volatile private var channelCreated = false

    /**
     * Build a [ForegroundInfo] for a worker. The notification id should be
     * stable per worker class so multiple periodic firings don't pile up
     * separate notifications; using `WorkerParameters.id.hashCode()` is
     * also fine if the worker only runs in one instance at a time.
     */
    fun buildForegroundInfo(
        context: Context,
        notificationId: Int,
        title: String,
        body: String,
    ): ForegroundInfo {
        ensureChannel(context)
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_cog)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
        return ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun ensureChannel(context: Context) {
        if (channelCreated) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = CHANNEL_DESC
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        manager.createNotificationChannel(channel)
        channelCreated = true
    }
}
