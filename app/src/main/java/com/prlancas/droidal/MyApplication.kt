package com.prlancas.droidal

import android.app.Application
import com.prlancas.droidal.brain.llm.LlmProviderFactory
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.lifecycle.AppForegroundTracker
import com.prlancas.droidal.memory.learning.LearningDatabase
import com.prlancas.droidal.memory.learning.workers.MemoryTidyWorker
import com.prlancas.droidal.memory.learning.workers.NewsScoutWorker
import com.prlancas.droidal.memory.learning.workers.ReflectorWorker
import com.prlancas.droidal.settings.SettingsRepository

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Config.init(this)

        // Track foreground state before anything else so the very first
        // MainActivity onStart is observed. Workers consult this to skip
        // running the LiteRT-LM Gemma engine load while the UI thread is
        // still bringing up CameraX / TextToSpeech / the recogniser — without
        // this guard the GPU contention from OpenCL kernel compilation
        // freezes the main thread and breaks the input dispatcher channel.
        AppForegroundTracker.register(this)

        // Touch the learning database so its FTS triggers exist before the
        // first conversation tries to write a turn.
        LearningDatabase.get(this).readableDatabase

        // Schedule the background learning workers. WorkManager uniques the
        // periodic registrations so calling this on every app start is
        // cheap. The actual cadence is read from SettingsRepository so the
        // user can rev / dampen the loops without code changes.
        val settings = SettingsRepository.get(this)
        if (settings.learningEnabled()) {
            ReflectorWorker.enqueuePeriodic(
                this,
                settings.reflectionIntervalHours().toLong(),
            )
            NewsScoutWorker.enqueuePeriodic(
                this,
                settings.newsScoutIntervalHours().toLong(),
            )
            MemoryTidyWorker.enqueuePeriodic(
                this,
                settings.memoryTidyIntervalHours().toLong(),
            )
        } else {
            ReflectorWorker.cancelPeriodic(this)
            NewsScoutWorker.cancelPeriodic(this)
            MemoryTidyWorker.cancelPeriodic(this)
        }

        // Kick off the on-device LiteRT-LM engine load now so the first
        // turn doesn't pay the multi-second `.litertlm` warm-up. Earlier
        // versions disabled this because the load appeared to lock up
        // the main thread — turned out the real failure was the GPU/
        // OpenCL allocation aborting at init when the engine was asked
        // for too large a `maxNumTokens`. With the token window clamped
        // by [SettingsRepository.clampLocalMaxNumTokens] the load
        // succeeds, and [LiteRtLmEngineCache.prewarm] is already
        // async-on-IO so it can never block this method. No-op when the
        // user hasn't picked a local model or hasn't downloaded one yet.
        runCatching { LlmProviderFactory.prewarmIfLocal(this) }
    }
}
