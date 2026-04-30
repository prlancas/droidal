package com.prlancas.droidal

import android.app.Application
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.memory.learning.LearningDatabase
import com.prlancas.droidal.memory.learning.workers.NewsScoutWorker
import com.prlancas.droidal.memory.learning.workers.ReflectorWorker
import com.prlancas.droidal.settings.SettingsRepository

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Config.init(this)

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
        } else {
            ReflectorWorker.cancelPeriodic(this)
            NewsScoutWorker.cancelPeriodic(this)
        }
    }
}
