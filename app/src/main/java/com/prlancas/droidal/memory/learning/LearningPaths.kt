package com.prlancas.droidal.memory.learning

import android.content.Context
import java.io.File

/**
 * Resolves on-device storage paths for the per-user learning store.
 *
 * Layout (mirrors hermes-agent's `~/.hermes/memories/` + `~/.hermes/skills/`):
 *
 * ```
 * filesDir/learning/
 *   users/
 *     <sanitizedUserId>/
 *       MEMORY.md
 *       USER.md
 *       skills/
 *         <slug>/
 *           SKILL.md
 *           ...supporting files
 * ```
 *
 * `userId` is sanitised so display names like `"Paul Lancaster"` map to
 * `"paul-lancaster"`. The literal token `"unknown"` is reserved for
 * sessions where face recognition has not yet identified the speaker.
 */
object LearningPaths {

    const val UNKNOWN_USER = "unknown"

    private const val ROOT_DIR = "learning"
    private const val USERS_DIR = "users"
    private const val SKILLS_DIR = "skills"
    const val MEMORY_FILE = "MEMORY.md"
    const val USER_FILE = "USER.md"
    const val SKILL_FILE = "SKILL.md"

    fun root(context: Context): File =
        File(context.applicationContext.filesDir, ROOT_DIR).also { it.mkdirs() }

    fun usersRoot(context: Context): File =
        File(root(context), USERS_DIR).also { it.mkdirs() }

    fun userDir(context: Context, userId: String): File {
        val safe = sanitize(userId)
        return File(usersRoot(context), safe).also { it.mkdirs() }
    }

    fun memoryFile(context: Context, userId: String): File =
        File(userDir(context, userId), MEMORY_FILE)

    fun userProfileFile(context: Context, userId: String): File =
        File(userDir(context, userId), USER_FILE)

    fun skillsDir(context: Context, userId: String): File =
        File(userDir(context, userId), SKILLS_DIR).also { it.mkdirs() }

    fun skillDir(context: Context, userId: String, slug: String): File =
        File(skillsDir(context, userId), sanitize(slug))

    fun skillFile(context: Context, userId: String, slug: String): File =
        File(skillDir(context, userId, slug), SKILL_FILE)

    /** Lowercase, kebab-case, alphanumerics + `-` only. */
    fun sanitize(raw: String): String {
        val trimmed = raw.trim().ifEmpty { UNKNOWN_USER }
        val mapped = buildString(trimmed.length) {
            for (ch in trimmed.lowercase()) {
                when {
                    ch.isLetterOrDigit() -> append(ch)
                    ch == '-' || ch == '_' -> append('-')
                    ch.isWhitespace() -> append('-')
                    else -> Unit
                }
            }
        }.trim('-')
        return mapped.ifEmpty { UNKNOWN_USER }
    }
}
