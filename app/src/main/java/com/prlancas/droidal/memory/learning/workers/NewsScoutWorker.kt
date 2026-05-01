package com.prlancas.droidal.memory.learning.workers

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prlancas.droidal.brain.Agent
import com.prlancas.droidal.debug.ConversationLog
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
 *   1. Read interest-flavoured entries from BOTH the user's USER.md and
 *      MEMORY.md (a simple heuristic — entries containing phrases like
 *      "interested in", "likes", "fan of" — keeps the parser robust
 *      without needing structured data). Memory is included because in
 *      practice Droidal records "X likes Y" facts there long before
 *      they get promoted into the more curated USER profile.
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

    override suspend fun getForegroundInfo(): ForegroundInfo =
        BackgroundWorkNotifications.buildForegroundInfo(
            context = applicationContext,
            notificationId = FOREGROUND_NOTIFICATION_ID,
            title = "Droidal — news scout",
            body = "Looking for things you might want to hear about.",
        )

    override suspend fun doWork(): Result = DebugBus.withActivity(DebugActivityState.NEWS_SCOUTING) {
        val ctx = applicationContext
        // Promote ourselves to a foreground service for the duration of the
        // run. WorkManager's default scheduler defers periodic work behind
        // Doze / app-standby on locked devices; foreground-service workers
        // are exempt and so will actually wake the radio + run the network
        // scout while the screen is off. The notification is on a MIN
        // importance channel so it sits silently in the shade.
        runCatching { setForeground(getForegroundInfo()) }
            .onFailure { Log.w(TAG, "Could not run as foreground worker: ${it.message}") }

        val settings = SettingsRepository.get(ctx)
        if (!settings.learningEnabled()) {
            ConversationLog.append(
                ConversationLog.Kind.INFO,
                "News scout: learning disabled — skipping",
            )
            return@withActivity Result.success()
        }
        val mode = settings.proactiveMode()
        ConversationLog.append(
            ConversationLog.Kind.INFO,
            "News scout: starting (proactiveMode=$mode)",
        )

        val store = LearningStore.get(ctx)
        val allUsers = store.listUsers()
        val users = allUsers.filter { it != LearningPaths.UNKNOWN_USER }
        if (users.isEmpty()) {
            ConversationLog.append(
                ConversationLog.Kind.INFO,
                "News scout: no known users (only '${LearningPaths.UNKNOWN_USER}'). " +
                    "Enrol a face to give Droidal someone to scout for.",
            )
        } else {
            ConversationLog.append(
                ConversationLog.Kind.INFO,
                "News scout: scouting for ${users.size} user(s): ${users.joinToString()}",
            )
        }

        var totalAdded = 0
        for (userId in users) {
            runCatching { totalAdded += scoutForUser(userId, store) }
                .onFailure {
                    Log.w(TAG, "Scout for $userId failed: ${it.message}")
                    ConversationLog.append(
                        ConversationLog.Kind.INFO,
                        "News scout: $userId failed — ${it.message ?: it::class.java.simpleName}",
                    )
                }
        }

        ConversationLog.append(
            ConversationLog.Kind.INFO,
            "News scout: finished (added=$totalAdded across ${users.size} user(s))",
        )

        if (mode == SettingsRepository.ProactiveMode.UNPROMPTED ||
            mode == SettingsRepository.ProactiveMode.BOTH
        ) {
            maybeStartProactive(store, settings)
        }
        Result.success()
    }

    /** @return how many news items were freshly upserted for this user. */
    private suspend fun scoutForUser(userId: String, store: LearningStore): Int {
        val profile = store.userProfileStore(userId).render()
        val memory = store.memoryStore(userId).render()
        if (profile.isBlank() && memory.isBlank()) {
            ConversationLog.append(
                ConversationLog.Kind.INFO,
                "News scout: $userId has an empty USER profile and MEMORY — nothing to seed search with",
            )
            return 0
        }
        val interests = extractInterests(profile, memory)
        if (interests.isEmpty()) {
            ConversationLog.append(
                ConversationLog.Kind.INFO,
                "News scout: $userId — no interests extracted from USER profile or MEMORY " +
                    "(needs phrases like 'interested in …', 'likes …', 'fan of …')",
            )
            return 0
        }
        ConversationLog.append(
            ConversationLog.Kind.INFO,
            "News scout: $userId interests = ${interests.take(5).joinToString()}",
        )
        val now = System.currentTimeMillis()
        var added = 0
        for (interest in interests.take(5)) {
            val query = "$interest news today"
            ConversationLog.append(
                ConversationLog.Kind.INFO,
                "News scout: searching DuckDuckGo for \"$query\"",
            )
            val results = WebSearch.search(query, max = 3)
            if (results.isEmpty()) {
                ConversationLog.append(
                    ConversationLog.Kind.INFO,
                    "News scout: no results for \"$query\" (DDG returned 0 — possibly rate-limited or offline)",
                )
                continue
            }
            ConversationLog.append(
                ConversationLog.Kind.INFO,
                "News scout: \"$query\" → ${results.size} result(s); top: ${results.first().title}",
            )
            for (r in results) {
                store.newsDao.upsert(userId, interest, r.title, r.snippet, r.url)
                added++
            }
        }
        store.curatorStateDao.setScouted(userId, now)
        return added
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

    companion object {
        private const val TAG = "NewsScoutWorker"
        private const val PERIODIC_NAME = "news-scout-periodic"
        // Stable id so successive periodic runs reuse the same shade entry
        // instead of stacking duplicates.
        private const val FOREGROUND_NOTIFICATION_ID = 0x4E575353 // "NWSS"

        private val INTEREST_RE = Regex(
            "(?:interested in|likes|loves|enjoys|fan of|into)\\s+(.+?)(?:\\.|;|\\n|$)",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Look for entries hinting at interests across one or more
         * sources (typically USER profile + MEMORY). Anything matching
         * `interested in X`, `likes X`, `fan of X`, etc. becomes an
         * interest token; the captured tail is split on commas / "and".
         * Keeping this regex-light avoids needing a structured tag
         * system in USER.md / MEMORY.md.
         *
         * Order is preserved (insertion-ordered set), so the first
         * interest mentioned wins limited search slots downstream.
         */
        @JvmStatic
        fun extractInterests(vararg sources: String): List<String> {
            val out = LinkedHashSet<String>()
            for (src in sources) {
                if (src.isBlank()) continue
                INTEREST_RE.findAll(src).forEach { m ->
                    m.groupValues[1]
                        .split(Regex(",| and "))
                        .map { it.trim().trim('.').trim() }
                        .filter { it.length in 3..40 }
                        .forEach { out += it }
                }
            }
            return out.toList()
        }

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
