package com.prlancas.droidal.memory.learning.workers

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prlancas.droidal.brain.Agent
import com.prlancas.droidal.debug.DebugActivityState
import com.prlancas.droidal.debug.DebugBus
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.StartConversation
import com.prlancas.droidal.memory.learning.LearningPaths
import com.prlancas.droidal.memory.learning.LearningStore
import com.prlancas.droidal.memory.learning.WebSearch
import com.prlancas.droidal.settings.SettingsRepository
import java.util.concurrent.TimeUnit

/**
 * Background news scout — Droidal's "find things the user might like and
 * start a conversation" loop.
 *
 * Mirrors hermes-agent's cron-driven web-search digests (see
 * [hermes-already-has-routines.md](../../../../../../../../../../../hermes-agent/hermes-already-has-routines.md)).
 *
 * For each scheduled run + known user:
 *   1. Read interest-flavoured entries from the user's USER.md (a simple
 *      heuristic — entries containing the substring "interested in" or
 *      "likes" — keeps the parser robust without needing structured data).
 *   2. DuckDuckGo-search "<interest> news today" via [WebSearch].
 *   3. Insert new results as `news_item` rows (UNIQUE(userId, url) handles
 *      dedup).
 *   4. If `proactiveMode` is `UNPROMPTED` or `BOTH`, and the user is not
 *      currently being talked to, and the cooldown has elapsed, publish a
 *      `StartConversation` primer asking Droidal to bring the news up.
 *
 * On `OFF` we still scout but never speak unprompted — items are surfaced
 * through `LearningStore.systemPromptBlock` so the next time the user
 * wakes Droidal it has news primers ready.
 */
class NewsScoutWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = DebugBus.withActivity(DebugActivityState.NEWS_SCOUTING) {
        val ctx = applicationContext
        val settings = SettingsRepository.get(ctx)
        if (!settings.learningEnabled()) return@withActivity Result.success()
        val mode = settings.proactiveMode()
        if (mode == SettingsRepository.ProactiveMode.OFF) {
            // Off still means "build news primers for next wake" — do not
            // skip the scout entirely, just suppress unprompted dialogue.
        }

        val store = LearningStore.get(ctx)
        val users = store.listUsers().filter { it != LearningPaths.UNKNOWN_USER }
        for (userId in users) {
            runCatching { scoutForUser(userId, store) }
                .onFailure { Log.w(TAG, "Scout for $userId failed: ${it.message}") }
        }

        if (mode == SettingsRepository.ProactiveMode.UNPROMPTED ||
            mode == SettingsRepository.ProactiveMode.BOTH
        ) {
            maybeStartProactive(store, settings)
        }
        Result.success()
    }

    private suspend fun scoutForUser(userId: String, store: LearningStore) {
        val interests = extractInterests(store.userProfileStore(userId).render())
        if (interests.isEmpty()) return
        val now = System.currentTimeMillis()
        for (interest in interests.take(5)) {
            val results = WebSearch.search("$interest news today", max = 3)
            for (r in results) {
                store.newsDao.upsert(userId, interest, r.title, r.snippet, r.url)
            }
        }
        store.curatorStateDao.setScouted(userId, now)
    }

    private fun maybeStartProactive(store: LearningStore, settings: SettingsRepository) {
        if (Agent.isChatting()) return
        val cooldownMs = settings.proactiveCooldownMinutes() * 60_000L
        val users = store.listUsers().filter { it != LearningPaths.UNKNOWN_USER }
        val now = System.currentTimeMillis()
        for (userId in users) {
            val pending = store.newsDao.pendingFor(userId, limit = 1).firstOrNull() ?: continue
            val state = store.curatorStateDao.get(userId)
            val lastSpoke = state.lastProactiveAt ?: 0L
            if (now - lastSpoke < cooldownMs) continue
            val primer = "Bring up this news item naturally with the user, then continue chatting: " +
                "${pending.title} (${pending.snippet}). Source: ${pending.url}."
            EventBus.publishAsync(
                StartConversation(startedByUser = false, message = primer, user = userId),
            )
            store.newsDao.markPresented(pending.id)
            store.curatorStateDao.setProactive(userId, now)
            return // one user per run
        }
    }

    /**
     * Look for entries hinting at interests. Anything matching `interested in
     * X` or `likes X` becomes an interest token; otherwise we fall back to
     * splitting on commas / "and". Keeping this regex-light avoids needing
     * a structured tag system in USER.md.
     */
    private fun extractInterests(profile: String): List<String> {
        if (profile.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        val likeRe = Regex("(?:interested in|likes|loves|enjoys|fan of|into)\\s+(.+?)(?:\\.|;|\\n|$)", RegexOption.IGNORE_CASE)
        likeRe.findAll(profile).forEach { m ->
            m.groupValues[1]
                .split(Regex(",| and "))
                .map { it.trim().trim('.').trim() }
                .filter { it.length in 3..40 }
                .forEach { out += it }
        }
        return out.toList()
    }

    companion object {
        private const val TAG = "NewsScoutWorker"
        private const val PERIODIC_NAME = "news-scout-periodic"

        fun enqueuePeriodic(context: Context, intervalHours: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<NewsScoutWorker>(
                intervalHours.coerceAtLeast(1L), TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .setInitialDelay(30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        }
    }
}
