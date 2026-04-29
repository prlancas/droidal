@file:OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)

package com.prlancas.droidal.brain.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.prlancas.droidal.settings.data.Model

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

    /** Read-and-clear the init warning, if any. */
    @Synchronized
    fun takeWarning(): String? {
        val w = lastInitWarning
        lastInitWarning = null
        return w
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
