@file:OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)

package com.prlancas.droidal.brain.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.prlancas.droidal.settings.data.Model
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide cache for a single loaded LiteRT-LM [Engine].
 *
 * Loading a multi-GB `.litertlm` file costs several seconds so we hold one
 * engine per `(modelName, commitHash)` and reuse it across every chat
 * session, image-description call, etc. [Conversation][com.google.ai.edge.litertlm.Conversation]s
 * on top of the engine are cheap and owned by the caller.
 *
 * If the model's preferred backend (often GPU) fails — e.g. "internal
 * error: 3rd_party error code:189" which usually indicates GPU
 * initialization failure — we automatically retry on CPU and report the
 * recovery via [lastInitWarning]. That keeps Droidal working on devices
 * whose GPU drivers can't actually handle the chosen Gemma/Gemma-3 model.
 *
 * [invalidate] is called from `MainActivity.onResume` after returning from
 * Settings so a freshly-activated model is picked up on the next turn.
 */
object LiteRtLmEngineCache {

    private const val TAG = "LiteRtLmEngineCache"

    private data class Key(val modelName: String, val commitHash: String)

    @Volatile private var cached: Pair<Key, Engine>? = null

    /**
     * Set after [getEngine] recovers from a failed initial attempt (e.g.
     * GPU → CPU fallback). Consume once via [takeWarning] so we don't
     * repeat the message on every conversation start.
     */
    @Volatile private var lastInitWarning: String? = null

    /** Background scope used by [prewarm] for off-thread engine load. */
    private val prewarmScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * In-flight prewarm job, if any. Held so a second [prewarm] call for
     * the same key can short-circuit instead of queuing another loader
     * behind the [getEngine] monitor.
     */
    private val prewarmJob = AtomicReference<Pair<Key, Job>?>(null)

    /** Read-and-clear the init warning, if any. */
    @Synchronized
    fun takeWarning(): String? {
        val w = lastInitWarning
        lastInitWarning = null
        return w
    }

    /**
     * True when [getEngine] for [model] would return without a slow
     * (multi-second) load — i.e. the engine for that exact `(name,
     * commitHash)` is already cached. Lets callers decide whether to
     * speak a "loading brain" filler before the next call to
     * [getEngine] / `newSession`.
     *
     * Deliberately lock-free (volatile read of [cached]) so a caller
     * checking this from `Agent.haveConversation` can't deadlock-or-stall
     * waiting for an in-flight [prewarm] to release the monitor — if the
     * background load is still running we want this to return false so
     * the brain-load filler fires.
     */
    fun isLoaded(model: Model): Boolean =
        cached?.first == Key(model.name, model.commitHash)

    /**
     * Kick off [getEngine] on a background thread for [model] if it
     * isn't already cached. Idempotent: a second call for the same key
     * while a load is still running is a cheap no-op. Errors are logged
     * but otherwise swallowed — the next real conversation will retry
     * via [getEngine] and surface the failure to the user there.
     *
     * Used from `MainActivity.onResume` so the multi-GB engine load
     * happens during the wake-word idle window, not during the gap
     * between "hey droidal" and the first reply.
     */
    fun prewarm(context: Context, model: Model) {
        val key = Key(model.name, model.commitHash)
        if (cached?.first == key) return
        val existing = prewarmJob.get()
        if (existing != null && existing.first == key && existing.second.isActive) return
        // App context only — never hold a per-Activity context in the
        // process-wide background scope.
        val appContext = context.applicationContext
        val job = prewarmScope.launch {
            try {
                getEngine(appContext, model)
                Log.i(TAG, "Prewarm completed for ${model.name}")
            } catch (t: Throwable) {
                Log.w(TAG, "Prewarm failed for ${model.name}: ${t.message}")
            }
        }
        // Stale entries (job.isActive == false after completion) are
        // harmless — the cached==key check above short-circuits the
        // next prewarm before this field is consulted.
        prewarmJob.set(key to job)
    }

    @Synchronized
    fun getEngine(context: Context, model: Model): Engine {
        val key = Key(model.name, model.commitHash)
        cached?.let { (cachedKey, engine) ->
            if (cachedKey == key) return engine
            Log.i(TAG, "Switching model (${cachedKey.modelName} -> ${key.modelName}); closing old engine")
            runCatching { engine.close() }.onFailure { Log.w(TAG, "old close: ${it.message}") }
        }
        val file = model.getFile(context)
        require(file.exists()) {
            "Local model file not found at ${file.absolutePath} — download it in settings first"
        }
        lastInitWarning = null
        val cfg = model.defaultConfig
        val maxTokens = cfg.maxTokens ?: 1024
        val preferredBackend = LiteRtLmProvider.backendFor(cfg.accelerators)
        val visionBackend = if (model.llmSupportImage) {
            LiteRtLmProvider.visionBackendFor(cfg.visionAccelerator)
        } else null

        val engine = tryInit(file.absolutePath, preferredBackend, visionBackend, maxTokens)
            ?: run {
                // Preferred backend (GPU/NPU) failed — retry on CPU. Gemma 3
                // LiteRT-LM models in particular often hit GPU driver bugs
                // on non-Pixel devices and surface as cryptic "3rd_party
                // error code: 189" exceptions.
                Log.w(TAG, "Preferred backend ($preferredBackend) failed, retrying on CPU")
                val recovered = tryInit(file.absolutePath, Backend.CPU(), null, maxTokens)
                    ?: throw IllegalStateException(
                        "Couldn't start LiteRT-LM on any backend. " +
                            "The model file may be incompatible with this device.",
                    )
                lastInitWarning = "Falling back to CPU — responses will be slower."
                recovered
            }
        cached = key to engine
        Log.i(TAG, "Initialized engine for ${model.name}")
        return engine
    }

    private fun tryInit(
        modelPath: String,
        backend: Backend,
        visionBackend: Backend?,
        maxTokens: Int,
    ): Engine? = try {
        val cfg = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            visionBackend = visionBackend,
            audioBackend = null,
            maxNumTokens = maxTokens,
        )
        Engine(cfg).also { it.initialize() }
    } catch (t: Throwable) {
        Log.w(TAG, "Engine init failed on $backend: ${t.message}")
        null
    }

    @Synchronized
    fun invalidate() {
        val current = cached ?: return
        runCatching { current.second.close() }.onFailure {
            Log.w(TAG, "invalidate close: ${it.message}")
        }
        cached = null
        lastInitWarning = null
    }
}
