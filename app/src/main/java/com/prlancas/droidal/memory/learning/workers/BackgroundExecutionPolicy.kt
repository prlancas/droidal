package com.prlancas.droidal.memory.learning.workers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Helpers for inspecting / nudging the OS-level background-execution
 * policy that affects Droidal's curator workers.
 *
 * The Samsung S23 (Droidal's reference device) is particularly aggressive:
 * once the app is "put to sleep", periodic [androidx.work.WorkManager]
 * runs slip until the user next opens the app. Promoting workers to
 * foreground services helps, but the most reliable cure is to ask the
 * user to exempt Droidal from battery optimisation.
 *
 * We don't fire `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` directly
 * (that's restricted by Play policy to a small set of app categories);
 * instead we open the system list at
 * `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` and let the user
 * find Droidal in it.
 */
object BackgroundExecutionPolicy {

    private const val TAG = "BackgroundExecPolicy"

    /**
     * @return `true` when the system reports that Droidal is currently
     * exempt from doze / app-standby battery optimisation. Returns `true`
     * defensively if the [PowerManager] isn't available.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.applicationContext
            .getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Open the system "Battery optimisation" list. The user can flip
     * Droidal from "Optimised" to "Don't optimise" there.
     *
     * Falls back to the per-app battery details screen, then the generic
     * application details screen, in case an OEM hides the master list.
     */
    fun openBatteryOptimizationSettings(context: Context) {
        val candidates = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            },
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Could not open ${intent.action}: ${e.message}")
            }
        }
    }
}
