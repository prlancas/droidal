package com.prlancas.droidal.memory.learning.workers

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.gson.JsonParser
import com.prlancas.droidal.brain.Agent
import com.prlancas.droidal.brain.llm.LlmProvider
import com.prlancas.droidal.brain.llm.LlmProviderFactory
import com.prlancas.droidal.brain.tools.DroidalTools
import com.prlancas.droidal.debug.ConversationLog
import com.prlancas.droidal.debug.DebugActivityState
import com.prlancas.droidal.debug.DebugBus
import com.prlancas.droidal.lifecycle.AppForegroundTracker
import com.prlancas.droidal.memory.learning.LearningPaths
import com.prlancas.droidal.memory.learning.LearningStore
import com.prlancas.droidal.memory.learning.MarkdownStore
import com.prlancas.droidal.memory.learning.MemoryTidier
import java.util.concurrent.TimeUnit

/**
 * Periodic background pass that keeps each user's MEMORY.md / USER.md
 * lean. Runs in two stages:
 *
 *  1. Deterministic dedupe — [MemoryTidier] folds exact-normalised
 *     duplicates and drops entries whose normalised form is a
 *     substring of another entry's. Fast, offline, and never asks the
 *     LLM. Always runs.
 *  2. LLM compress — when a store is over [LLM_COMPRESS_THRESHOLD] of
 *     its character budget after step 1, the worker asks the
 *     configured LLM to rewrite each entry shorter and denser. The
 *     model's output is parsed as a JSON array of strings and only
 *     accepted if the rewrite is strictly smaller than the input and
 *     produces at least one entry. Failures fall back to the
 *     deterministic-only result.
 *
 * Guard rails (same as [ReflectorWorker]):
 *
 *  - Skipped while the app is in the foreground — LiteRT-LM cannot
 *    share the EGL context with HWUI without breaking input dispatch.
 *  - Skipped while [Agent.isChatting] — never compete with a live
 *    conversation for the engine.
 *  - Runs as a foreground service so a locked / dozing device doesn't
 *    silently shelve the LLM call mid-rewrite.
 *
 * Why a dedicated worker rather than extending [ReflectorWorker]:
 *   reflection extracts facts from the transcript and adds new
 *   memories — this worker only operates on the existing set. Running
 *   the two on different cadences (reflection more often, tidy
 *   slower) keeps both responsibilities testable in isolation and
 *   stops them stepping on each other's writes during a single LLM
 *   round-trip.
 */
class MemoryTidyWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        BackgroundWorkNotifications.buildForegroundInfo(
            context = applicationContext,
            notificationId = FOREGROUND_NOTIFICATION_ID,
            title = "Droidal — tidying memories",
            body = "Removing duplicates and tightening up stored notes.",
        )

    override suspend fun doWork(): Result {
        if (AppForegroundTracker.isForeground()) {
            // Same rationale as [ReflectorWorker]: don't compile Gemma
            // subgraphs on the GPU/EGL context the live face is
            // rendering through.
            Log.i(TAG, "Memory tidy deferred: app is in the foreground")
            return Result.retry()
        }
        if (Agent.isChatting()) {
            return Result.retry()
        }
        runCatching { setForeground(getForegroundInfo()) }
            .onFailure { Log.w(TAG, "Could not run as foreground worker: ${it.message}") }

        return DebugBus.withActivity(DebugActivityState.REFLECTING, detail = "tidy") {
            runCatching { tidyAll() }
                .getOrElse {
                    Log.w(TAG, "Memory tidy failed: ${it.message}")
                    Result.retry()
                }
        }
    }

    private suspend fun tidyAll(): Result {
        val store = LearningStore.get(applicationContext)
        val users = store.listUsers().filter { it != LearningPaths.UNKNOWN_USER }
        if (users.isEmpty()) {
            ConversationLog.append(
                ConversationLog.Kind.INFO,
                "Memory tidy: no known users — nothing to do",
            )
            return Result.success()
        }
        var totalDropped = 0
        var llmRewrites = 0
        // Lazily build a single LlmProvider so we don't pay engine
        // load cost when every store ends up under the threshold.
        var provider: LlmProvider? = null
        for (userId in users) {
            for (target in TARGETS) {
                val markdownStore = store.storeFor(userId, target)
                val (dropped, didRewrite, lazyProvider) = tidyOne(
                    userId = userId,
                    target = target,
                    markdownStore = markdownStore,
                    existing = provider,
                )
                totalDropped += dropped
                if (didRewrite) llmRewrites++
                provider = lazyProvider
            }
        }
        provider?.close()
        ConversationLog.append(
            ConversationLog.Kind.INFO,
            "Memory tidy: finished (dropped=$totalDropped, llmRewrites=$llmRewrites " +
                "across ${users.size} user(s))",
        )
        return Result.success()
    }

    /**
     * @return (dropped count, did-llm-rewrite, lazy provider). The
     * provider passed in is reused across calls so we only initialise
     * the LiteRT-LM engine once per worker run.
     */
    private suspend fun tidyOne(
        userId: String,
        target: String,
        markdownStore: MarkdownStore,
        existing: LlmProvider?,
    ): TidyOutcome {
        val before = markdownStore.read()
        if (before.isEmpty()) return TidyOutcome(0, false, existing)
        val deterministic = MemoryTidier.tidy(before)
        if (deterministic.droppedCount > 0) {
            markdownStore.rewrite(deterministic.entries)
            ConversationLog.append(
                ConversationLog.Kind.INFO,
                "Memory tidy: $userId/$target dropped ${deterministic.droppedCount} " +
                    "duplicate/covered entry(s)",
            )
        }
        val after = deterministic.entries
        val used = blobLength(after)
        if (!shouldRunLlm(markdownStore.limit(), used, after.size)) {
            return TidyOutcome(deterministic.droppedCount, false, existing)
        }
        val ctx = LlmStageContext(
            userId = userId,
            target = target,
            markdownStore = markdownStore,
            after = after,
            usedChars = used,
            originalSize = before.size,
            droppedSoFar = deterministic.droppedCount,
        )
        return runLlmStage(ctx, existing)
    }

    /**
     * Bundle of read-only inputs handed to [runLlmStage] so it stays
     * under detekt's `LongParameterList` ceiling.
     */
    private data class LlmStageContext(
        val userId: String,
        val target: String,
        val markdownStore: MarkdownStore,
        val after: List<String>,
        val usedChars: Int,
        val originalSize: Int,
        val droppedSoFar: Int,
    )

    private fun shouldRunLlm(limit: Int, used: Int, entryCount: Int): Boolean {
        if (limit <= 0) return false
        if (used.toDouble() / limit < LLM_COMPRESS_THRESHOLD) return false
        return entryCount >= MIN_ENTRIES_FOR_LLM
    }

    private fun blobLength(entries: List<String>): Int =
        entries.sumOf { it.length } +
            (entries.size - 1).coerceAtLeast(0) * MarkdownStore.ENTRY_DELIMITER.length

    /**
     * Second stage of [tidyOne] — runs the optional LLM rewrite once
     * we've decided the deterministic result is still too dense to
     * leave alone. Kept as its own function so [tidyOne] doesn't
     * accumulate enough early-exit returns to trip detekt's
     * `ReturnCount` rule.
     */
    private suspend fun runLlmStage(ctx: LlmStageContext, existing: LlmProvider?): TidyOutcome {
        val provider = existing ?: runCatching { LlmProviderFactory.current(applicationContext) }
            .onFailure { Log.w(TAG, "No LLM provider for tidy: ${it.message}") }
            .getOrNull()
            ?: return TidyOutcome(ctx.droppedSoFar, false, null)

        val rewritten = compressWithLlm(provider, ctx.userId, ctx.target, ctx.after).orEmpty()
        if (rewritten.isEmpty()) return TidyOutcome(ctx.droppedSoFar, false, provider)

        val rewrittenChars = blobLength(rewritten)
        // Only accept the rewrite if it's actually smaller — otherwise
        // we'd churn the file without buying any context room.
        if (rewrittenChars >= ctx.usedChars) {
            ConversationLog.append(
                ConversationLog.Kind.INFO,
                "Memory tidy: ${ctx.userId}/${ctx.target} LLM rewrite was not smaller " +
                    "(was ${ctx.usedChars}, now $rewrittenChars) — keeping deterministic result",
            )
            return TidyOutcome(ctx.droppedSoFar, false, provider)
        }
        ctx.markdownStore.rewrite(rewritten)
        ConversationLog.append(
            ConversationLog.Kind.INFO,
            "Memory tidy: ${ctx.userId}/${ctx.target} LLM rewrite ${ctx.originalSize}→${rewritten.size} " +
                "entries (${ctx.usedChars}→$rewrittenChars chars)",
        )
        return TidyOutcome(ctx.droppedSoFar, true, provider)
    }

    private suspend fun compressWithLlm(
        provider: LlmProvider,
        userId: String,
        target: String,
        entries: List<String>,
    ): List<String>? {
        val prompt = buildString {
            appendLine("Rewrite the following memory entries for user '$userId' (file: $target).")
            appendLine("Goal: information-dense, short notes — fewer characters, no lost facts.")
            appendLine("Rules:")
            appendLine("- Combine entries that describe the same thing.")
            appendLine("- Keep durable facts (preferences, names, equipment, relationships).")
            appendLine("- Drop small talk, transient context, and anything redundant with another entry.")
            appendLine("- Reply with ONLY a JSON array of strings (one string per surviving entry).")
            appendLine()
            entries.forEachIndexed { idx, entry ->
                appendLine("${idx + 1}. $entry")
            }
        }
        val session = provider.newSession(systemPrompt = TIDY_SYSTEM, tools = DroidalTools())
        val raw = try {
            session.send(prompt)
        } catch (t: Throwable) {
            Log.w(TAG, "Memory tidy LLM call failed: ${t.message}")
            return null
        } finally {
            runCatching { session.close() }
        }
        return parseJsonArray(raw)
    }

    private fun parseJsonArray(reply: String): List<String>? {
        if (reply.isBlank()) return null
        val cleaned = reply.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val first = cleaned.indexOf('[')
        val last = cleaned.lastIndexOf(']')
        if (first < 0 || last <= first) return null
        val slice = cleaned.substring(first, last + 1)
        return runCatching {
            JsonParser.parseString(slice).asJsonArray
                .mapNotNull { el ->
                    if (el.isJsonNull) {
                        null
                    } else {
                        el.asString.trim().takeIf { it.isNotEmpty() }
                    }
                }
        }.getOrElse {
            Log.w(TAG, "Could not parse tidy JSON: ${it.message}")
            null
        }
    }

    private data class TidyOutcome(
        val dropped: Int,
        val didRewrite: Boolean,
        val provider: LlmProvider?,
    )

    companion object {
        private const val TAG = "MemoryTidyWorker"
        private const val PERIODIC_NAME = "memory-tidy-periodic"
        private const val ONESHOT_NAME = "memory-tidy-once"

        // Stable id so successive periodic runs reuse the same shade
        // entry instead of stacking duplicates.
        private const val FOREGROUND_NOTIFICATION_ID = 0x4D54444E // "MTDN"

        private val TARGETS = listOf(LearningStore.TARGET_MEMORY, LearningStore.TARGET_USER)

        /**
         * Only ask the LLM to compress a store once it crosses this
         * fraction of its character budget. Below the threshold the
         * deterministic dedupe is already enough to keep the system
         * prompt lean.
         */
        private const val LLM_COMPRESS_THRESHOLD = 0.6

        /** Don't pay LLM-load cost to compress one or two entries. */
        private const val MIN_ENTRIES_FOR_LLM = 3

        private val TIDY_SYSTEM = """
            You are Droidal's memory tidier. You compress an existing list of
            short notes into a denser, shorter list of equivalent notes without
            losing durable facts. Respond with ONLY a JSON array of strings.
        """.trimIndent()

        fun enqueuePeriodic(context: Context, intervalHours: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()
            val request = PeriodicWorkRequestBuilder<MemoryTidyWorker>(
                intervalHours.coerceAtLeast(1L),
                TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .setInitialDelay(45, TimeUnit.MINUTES)
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

        /** Manual trigger from the Learning settings UI. */
        fun enqueueOneShot(context: Context) {
            val request = OneTimeWorkRequestBuilder<MemoryTidyWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONESHOT_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
