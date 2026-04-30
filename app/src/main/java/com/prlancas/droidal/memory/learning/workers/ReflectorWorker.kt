package com.prlancas.droidal.memory.learning.workers

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.prlancas.droidal.brain.llm.LlmProviderFactory
import com.prlancas.droidal.brain.tools.DroidalTools
import com.prlancas.droidal.debug.DebugActivityState
import com.prlancas.droidal.debug.DebugBus
import com.prlancas.droidal.memory.learning.LearningDatabase
import com.prlancas.droidal.memory.learning.LearningPaths
import com.prlancas.droidal.memory.learning.LearningStore
import java.util.concurrent.TimeUnit

/**
 * Background reflection — Droidal's "constant improvement" loop.
 *
 * Mirrors hermes-agent's [agent/curator.py](../../../../../../../../../../../hermes-agent/agent/curator.py)
 * (idle-triggered review of the agent's own learning) plus the
 * `on_session_end` extraction hook from
 * [agent/memory_provider.py](../../../../../../../../../../../hermes-agent/agent/memory_provider.py).
 *
 * For each scheduled run + userId:
 *   1. Skip if we're mid-conversation, or there are no new turns since
 *      `lastReflectedAt`.
 *   2. Build a refinement prompt: current MEMORY.md, USER.md, skills index,
 *      and the recent transcript window.
 *   3. Ask an auxiliary LLM session to emit a JSON edit list:
 *      `[{op:"memoryAdd", target:"user", content:"..."}, ...]`.
 *   4. Apply edits via [LearningStore] / `SkillStore`. Honour char limits.
 *   5. Stamp `lastReflectedAt`.
 *
 * Two entry points:
 * - [enqueueOneShot] is called by `Agent` at session end so the model can
 *   promote facts while the conversation is warm.
 * - [enqueuePeriodic] is called from `MyApplication.onCreate` for ongoing
 *   curation when the device is idle.
 */
class ReflectorWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val userId = inputData.getString(KEY_USER_ID)?.takeIf { it.isNotBlank() }
            ?: return Result.success()

        if (com.prlancas.droidal.brain.Agent.isChatting()) {
            // Don't compete with an active conversation for the LLM session.
            return Result.retry()
        }

        return DebugBus.withActivity(DebugActivityState.REFLECTING, detail = userId) {
            runCatching { reflect(userId) }
                .getOrElse {
                    Log.w(TAG, "Reflection failed for $userId: ${it.message}")
                    Result.retry()
                }
        }
    }

    private suspend fun reflect(userId: String): Result {
        val store = LearningStore.get(applicationContext)
        val state = store.curatorStateDao.get(userId)
        val since = state.lastReflectedAt ?: 0L
        val turns = store.conversationDao.turnsSince(userId, since, limit = 80)
        if (turns.isEmpty()) {
            store.curatorStateDao.setReflected(userId, System.currentTimeMillis())
            return Result.success()
        }

        val transcript = turns.joinToString("\n") { t ->
            val role = if (t.role == LearningDatabase.ROLE_USER) "USER" else "DROIDAL"
            "$role: ${t.text}"
        }
        val memory = store.memoryStore(userId).render()
        val profile = store.userProfileStore(userId).render()
        val skills = store.skills(userId).list().joinToString("\n") { "- ${it.slug}: ${it.description}" }

        val prompt = buildPrompt(userId, memory, profile, skills, transcript)
        val provider = runCatching { LlmProviderFactory.current(applicationContext) }
            .getOrElse {
                Log.w(TAG, "No LLM provider for reflection: ${it.message}")
                return Result.retry()
            }
        // Reflection is read-only of the world (no robot/move tools), so we
        // pass an empty ToolSet via DroidalTools but instruct the model to
        // respond with a single JSON edit list and nothing else.
        val session = provider.newSession(systemPrompt = REFLECTOR_SYSTEM, tools = DroidalTools())
        val rawReply = try {
            session.send(prompt)
        } finally {
            session.close()
        }

        val edits = parseEdits(rawReply)
        Log.i(TAG, "Reflection for '$userId' produced ${edits.size} edits from ${turns.size} turns")
        applyEdits(userId, store, edits)
        store.curatorStateDao.setReflected(userId, System.currentTimeMillis())
        return Result.success()
    }

    private fun buildPrompt(
        userId: String,
        memory: String,
        profile: String,
        skills: String,
        transcript: String,
    ): String = """
        Recent conversation transcript with user '$userId':
        ----------
        ${transcript.take(8_000)}
        ----------
        
        Current MEMORY.md (Droidal's notes):
        ${memory.ifBlank { "(empty)" }}
        
        Current USER.md ($userId):
        ${profile.ifBlank { "(empty)" }}
        
        Existing skills:
        ${skills.ifBlank { "(none)" }}
        
        Propose a JSON edit list (array). Use only these ops:
        - {"op":"memoryAdd","target":"user"|"memory","content":"..."}
        - {"op":"memoryReplace","target":"user"|"memory","oldText":"...","content":"..."}
        - {"op":"memoryRemove","target":"user"|"memory","oldText":"..."}
        - {"op":"skillCreate","name":"kebab-slug","content":"<full SKILL.md>"}
        - {"op":"skillPatch","name":"kebab-slug","oldString":"...","newString":"..."}
        - {"op":"skillDelete","name":"kebab-slug"}
        
        Rules:
        - Promote durable preferences / facts / interests to USER.md, environment / robot facts to MEMORY.md.
        - Skip ephemera (small talk, weather, "thanks").
        - Never include credentials or invasive personal data.
        - Reply with ONLY a JSON array, no prose, no fences.
    """.trimIndent()

    private fun applyEdits(userId: String, store: LearningStore, edits: List<JsonObject>) {
        val skills = store.skills(userId)
        for (edit in edits) {
            val op = edit.get("op")?.asString ?: continue
            try {
                when (op) {
                    "memoryAdd" -> store.storeFor(userId, edit.optStr("target", "memory"))
                        .add(edit.optStr("content", ""))
                    "memoryReplace" -> store.storeFor(userId, edit.optStr("target", "memory"))
                        .replace(edit.optStr("oldText", ""), edit.optStr("content", ""))
                    "memoryRemove" -> store.storeFor(userId, edit.optStr("target", "memory"))
                        .remove(edit.optStr("oldText", ""))
                    "skillCreate" -> skills.create(
                        slug = LearningPaths.sanitize(edit.optStr("name", "skill")),
                        content = edit.optStr("content", ""),
                    )
                    "skillPatch" -> skills.patch(
                        slug = LearningPaths.sanitize(edit.optStr("name", "")),
                        oldString = edit.optStr("oldString", ""),
                        newString = edit.optStr("newString", ""),
                    )
                    "skillDelete" -> skills.delete(LearningPaths.sanitize(edit.optStr("name", "")))
                    else -> Log.w(TAG, "Unknown op '$op' in reflection edit")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Edit '$op' failed: ${e.message}")
            }
        }
    }

    private fun parseEdits(reply: String): List<JsonObject> {
        if (reply.isBlank()) return emptyList()
        val cleaned = reply.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val firstBracket = cleaned.indexOf('[')
        val lastBracket = cleaned.lastIndexOf(']')
        if (firstBracket < 0 || lastBracket <= firstBracket) return emptyList()
        val slice = cleaned.substring(firstBracket, lastBracket + 1)
        return runCatching {
            JsonParser.parseString(slice).asJsonArray
                .filter { it.isJsonObject }
                .map { it.asJsonObject }
        }.getOrElse {
            Log.w(TAG, "Could not parse reflection JSON: ${it.message}")
            emptyList()
        }
    }

    private fun JsonObject.optStr(key: String, default: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: default

    companion object {
        private const val TAG = "ReflectorWorker"
        const val KEY_USER_ID = "userId"
        private const val ONESHOT_PREFIX = "reflector-once-"
        private const val PERIODIC_PREFIX = "reflector-periodic-"

        private val REFLECTOR_SYSTEM = """
            You are Droidal's reflection assistant. You audit one recent conversation
            and propose targeted updates to its persistent learning files.
            Respond with a JSON array of edit objects (and nothing else).
        """.trimIndent()

        fun enqueueOneShot(context: Context, userId: String) {
            val safe = LearningPaths.sanitize(userId)
            val request = OneTimeWorkRequestBuilder<ReflectorWorker>()
                .setInputData(workDataOf(KEY_USER_ID to safe))
                .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONESHOT_PREFIX + safe,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        /**
         * Schedules a periodic curator run. WorkManager will invoke us with
         * the device idle and a network connection; we use this slot to run
         * reflection over every known user. The actual hourly cadence is
         * driven by `SettingsRepository.reflectionIntervalHours`.
         */
        fun enqueuePeriodic(context: Context, intervalHours: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<PeriodicReflectorWorker>(
                intervalHours.coerceAtLeast(1L), TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_PREFIX + "all",
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_PREFIX + "all")
        }
    }
}

/**
 * Periodic fan-out: enqueues a one-shot [ReflectorWorker] for each known
 * user. Keeps the per-user dispatch logic in one place.
 */
class PeriodicReflectorWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = LearningStore.get(applicationContext)
        store.listUsers().forEach { userId ->
            ReflectorWorker.enqueueOneShot(applicationContext, userId)
        }
        return Result.success()
    }
}

/** Simplified view; allows tests to drive the worker without WorkManager. */
internal fun reflectorOneShotData(userId: String): Data = workDataOf(ReflectorWorker.KEY_USER_ID to userId)
