package com.prlancas.droidal.memory.learning

import android.content.Context
import android.util.Log
import java.io.File

/**
 * File-system index of agent-managed skills under
 * `filesDir/learning/users/<userId>/skills/<slug>/SKILL.md`.
 *
 * Mirrors the directory layout used by hermes-agent's
 * [skill_manager_tool.py](../../../../../../../../../../hermes-agent/tools/skill_manager_tool.py)
 * and the agent-skills.io `SKILL.md` shape (markdown front-matter + body).
 *
 * `SkillStore` owns the on-disk shape only. Schema for the LLM-facing
 * tools lives in [com.prlancas.droidal.brain.tools.DroidalTools].
 */
class SkillStore(
    private val context: Context,
    private val userId: String,
) {

    companion object {
        private const val TAG = "SkillStore"
    }

    data class SkillSummary(
        val slug: String,
        val name: String,
        val description: String,
        val updatedAtMs: Long,
    )

    fun list(): List<SkillSummary> {
        val dir = LearningPaths.skillsDir(context, userId)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { readSummary(it) }
            .sortedByDescending { it.updatedAtMs }
    }

    fun read(slug: String): String? {
        val file = LearningPaths.skillFile(context, userId, slug)
        return if (file.exists()) runCatching { file.readText(Charsets.UTF_8) }.getOrNull() else null
    }

    fun readFile(slug: String, relativePath: String): String? {
        val safeRel = sanitizeRelativePath(relativePath) ?: return null
        val file = File(LearningPaths.skillDir(context, userId, slug), safeRel)
        return if (file.exists() && file.isFile) {
            runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        } else null
    }

    fun create(slug: String, content: String): Boolean {
        if (slug.isBlank() || content.isBlank()) return false
        val file = LearningPaths.skillFile(context, userId, slug)
        file.parentFile?.mkdirs()
        return runCatching {
            file.writeText(content.trim() + "\n", Charsets.UTF_8)
            true
        }.getOrElse {
            Log.w(TAG, "create $slug failed: ${it.message}")
            false
        }
    }

    fun edit(slug: String, content: String): Boolean = create(slug, content)

    fun patch(slug: String, oldString: String, newString: String): Pair<Boolean, String> {
        val file = LearningPaths.skillFile(context, userId, slug)
        if (!file.exists()) return false to "Skill '$slug' does not exist."
        val current = runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse { return false to "Cannot read SKILL.md: ${it.message}" }
        val occurrences = current.windowedSequence(oldString.length, 1)
            .count { it == oldString }
        if (occurrences == 0) return false to "old_string not found in skill '$slug'."
        if (occurrences > 1) return false to "old_string matches $occurrences locations in '$slug' — make it unique."
        val updated = current.replaceFirst(oldString, newString)
        return runCatching {
            file.writeText(updated, Charsets.UTF_8)
            true to "Patched."
        }.getOrElse { false to "Write failed: ${it.message}" }
    }

    fun delete(slug: String): Boolean {
        val dir = LearningPaths.skillDir(context, userId, slug)
        return dir.deleteRecursively()
    }

    fun writeFile(slug: String, relativePath: String, content: String): Boolean {
        val safeRel = sanitizeRelativePath(relativePath) ?: return false
        val target = File(LearningPaths.skillDir(context, userId, slug), safeRel)
        target.parentFile?.mkdirs()
        return runCatching {
            target.writeText(content, Charsets.UTF_8)
            true
        }.getOrElse {
            Log.w(TAG, "writeFile $slug/$safeRel failed: ${it.message}")
            false
        }
    }

    fun removeFile(slug: String, relativePath: String): Boolean {
        val safeRel = sanitizeRelativePath(relativePath) ?: return false
        val file = File(LearningPaths.skillDir(context, userId, slug), safeRel)
        return file.exists() && file.delete()
    }

    private fun readSummary(dir: File): SkillSummary? {
        val file = File(dir, LearningPaths.SKILL_FILE)
        if (!file.exists()) return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val front = parseFrontMatter(text)
        val name = front["name"] ?: dir.name
        val description = front["description"] ?: firstSentenceOfBody(text)
        return SkillSummary(
            slug = dir.name,
            name = name,
            description = description,
            updatedAtMs = file.lastModified(),
        )
    }

    private fun parseFrontMatter(text: String): Map<String, String> {
        if (!text.startsWith("---")) return emptyMap()
        val end = text.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap()
        val body = text.substring(3, end).trim()
        return body.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null else {
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim().trim('"')
                    key to value
                }
            }
            .toMap()
    }

    private fun firstSentenceOfBody(text: String): String {
        val body = text.substringAfter("\n---", text).substringAfter("---")
        val candidate = body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        return candidate?.take(120) ?: ""
    }

    /**
     * Reject relative paths that escape the skill directory or use
     * absolute / unusual prefixes. Allows kebab/snake names + nested
     * folders like `references/issue-taxonomy.md`.
     */
    private fun sanitizeRelativePath(rel: String): String? {
        if (rel.isBlank()) return null
        val normalised = rel.trim().replace('\\', '/')
        if (normalised.startsWith('/')) return null
        if (".." in normalised.split('/')) return null
        return normalised
    }
}
