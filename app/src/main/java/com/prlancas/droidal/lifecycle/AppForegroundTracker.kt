package com.prlancas.droidal.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide tracker for whether at least one Activity is currently in
 * the started state — i.e. is Droidal's UI visible to the user right now.
 *
 * Why this exists:
 *
 * WorkManager replays any unfinished workers as soon as the process is
 * created. On Droidal's startup path the main thread is busy bringing up
 * CameraX, ML Kit face detection, Porcupine wake-word, the HWUI Vulkan
 * renderer, and TextToSpeech — and the LiteRT-LM SDK reuses the app's
 * EGL environment for its OpenCL kernel compilation. If a `ReflectorWorker`
 * fires while that's happening, the GPU contention freezes the main
 * thread, ART starts logging
 *   `userfaultfd: MOVE ioctl seems unsupported: Connection timed out`
 * and the input dispatcher channel breaks ("Channel is unrecoverably
 * broken and will be disposed!"). The activity is killed before it can
 * finish booting — exactly the symptom we've been chasing in `MainActivity`.
 *
 * `Agent.isChatting()` doesn't catch this case: at the moment the worker
 * dispatches, no conversation is in flight yet — the user hasn't said
 * the wake word — but the UI thread is still very much under load.
 *
 * Workers consult [isForeground] at the top of `doWork()` and bail with
 * `Result.retry()` while the app is in the foreground, deferring the
 * heavy LiteRT-LM Gemma engine load to a later WorkManager attempt when
 * the screen is off / the activity has been backgrounded and the GPU is
 * idle.
 */
object AppForegroundTracker {

    private val startedActivities = AtomicInteger(0)
    private val registered = AtomicBoolean(false)

    /** True when at least one activity in this process is in the started state. */
    fun isForeground(): Boolean = startedActivities.get() > 0

    /**
     * Hook the tracker into the application's activity lifecycle. Idempotent
     * — calling this more than once does not double-count started activities.
     * Call from `Application.onCreate` so the very first activity's
     * `onStart` is observed.
     */
    fun register(application: Application) {
        if (registered.compareAndSet(false, true)) {
            application.registerActivityLifecycleCallbacks(callbacks)
        }
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) {
            startedActivities.incrementAndGet()
        }
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) {
            // Clamp at zero so an unmatched onStopped (e.g. test harness that
            // bypassed onStarted) can't drive the counter negative and
            // permanently strand workers thinking we're foregrounded.
            startedActivities.updateAndGet { (it - 1).coerceAtLeast(0) }
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    // --- Test hooks -------------------------------------------------------
    // JVM unit tests can drive the foreground state directly without having
    // to spin up an Android Activity. Marked `internal` so they don't leak
    // into the rest of the app's API surface.

    internal fun notifyStartedForTest() {
        startedActivities.incrementAndGet()
    }

    internal fun notifyStoppedForTest() {
        startedActivities.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    internal fun resetForTest() {
        startedActivities.set(0)
        registered.set(false)
    }
}
