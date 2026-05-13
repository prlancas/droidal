package com.prlancas.droidal.memory.learning

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/**
 * `§`-delimited markdown entry store, mirroring hermes-agent's
 * [tools/memory_tool.py](../../../../../../../../../../hermes-agent/tools/memory_tool.py)
 * `MemoryStore`.
 *
 * Each backing file contains zero or more entries separated by:
 *
 * ```
 * \n§\n
 * ```
 *
 * Operations honour a hard character cap (so we don't blow up the system
 * prompt) and run a lightweight injection-pattern scan before accepting
 * writes — the contents are injected into the LLM system prompt and must
 * not contain prompt-hijack / exfiltration payloads.
 *
 * Concurrency: a sibling `<file>.lock` file is held during read-modify-
 * write to coordinate with background workers (e.g. `Reflector`).
 */
class MarkdownStore(
    private val file: File,
    private val charLimit: Int,
) {

    companion object {
        const val ENTRY_DELIMITER = "\n§\n"
        private const val TAG = "MarkdownStore"
        private val WHITESPACE_RUN = Regex("\\s+")

        // All ASCII punctuation is collapsed to whitespace during
        // normalisation so a reflector summary with an Oxford comma
        // ("MacBook, a Linux box, and a Windows box") matches the
        // narrower original without one ("MacBook, a Linux box and a
        // Windows box"), and a trailing period doesn't make
        // "Likes coffee." look different from "Likes coffee".
        private val PUNCTUATION = Regex("[\\p{Punct}]+")

        /**
         * Whitespace-and-punctuation-folded lowercase form used by the
         * coverage check inside [add] and by [MemoryTidier] when it
         * looks for redundant entries during the background tidy pass.
         * Exposed so the tidier doesn't need to duplicate the rules.
         */
        fun normaliseForCompare(s: String): String =
            s.lowercase()
                .replace(PUNCTUATION, " ")
                .replace(WHITESPACE_RUN, " ")
                .trim()
    }

    data class Result(
        val success: Boolean,
        val message: String,
        val entries: List<String>,
        val usedChars: Int,
    )

    fun read(): List<String> = withLock { readEntriesUnlocked() }

    fun usedChars(): Int = entriesToBlob(read()).length

    fun limit(): Int = charLimit

    fun add(content: String): Result {
        val cleaned = content.trim()
        if (cleaned.isEmpty()) {
            return failure("Content cannot be empty.")
        }
        val scanError = MemoryThreatScanner.scan(cleaned)
        if (scanError != null) return failure(scanError)
        val cleanedNorm = normalise(cleaned)

        return withLock {
            val current = readEntriesUnlocked()
            // Already covered by an existing (broader-or-equal) entry —
            // drop the duplicate. Compared with normalised whitespace
            // and trailing punctuation so "MacBook, a Linux box, and a
            // Windows box." matches "MacBook, a Linux box and a
            // Windows box".
            if (current.any { it.coversNorm(cleanedNorm) }) {
                return@withLock Result(
                    true,
                    "Entry already covered by an existing memory.",
                    current,
                    entriesToBlob(current).length,
                )
            }
            // The new entry strictly subsumes one or more existing
            // entries (e.g. the reflector worker emitting a combined
            // summary of two narrower facts). Drop the narrower
            // entries and keep the broader new one — otherwise the
            // overlapping entries would coexist and bloat the system
            // prompt.
            val survivors = current.filterNot { existing ->
                val existingNorm = normalise(existing)
                existingNorm.isNotEmpty() && cleanedNorm.contains(existingNorm)
            }
            val droppedCount = current.size - survivors.size
            val candidate = survivors + cleaned
            val newSize = entriesToBlob(candidate).length
            if (newSize > charLimit) {
                return@withLock Result(
                    success = false,
                    message = "Memory at ${entriesToBlob(current).length}/$charLimit chars. " +
                        "Adding this entry (${cleaned.length} chars) would exceed the limit. " +
                        "Replace or remove existing entries first.",
                    entries = current,
                    usedChars = entriesToBlob(current).length,
                )
            }
            writeEntriesUnlocked(candidate)
            val message = if (droppedCount > 0) {
                val noun = if (droppedCount == 1) "narrower entry" else "narrower entries"
                "Entry added (replaced $droppedCount $noun)."
            } else {
                "Entry added."
            }
            Result(true, message, candidate, newSize)
        }
    }

    private fun String.coversNorm(otherNorm: String): Boolean {
        val selfNorm = normalise(this)
        return selfNorm.isNotEmpty() && selfNorm.contains(otherNorm)
    }

    private fun normalise(s: String): String = normaliseForCompare(s)

    fun replace(oldText: String, newContent: String): Result {
        val needle = oldText.trim()
        val replacement = newContent.trim()
        if (needle.isEmpty()) return failure("oldText cannot be empty.")
        if (replacement.isEmpty()) {
            return failure("newContent cannot be empty. Use 'remove' to delete entries.")
        }
        val scanError = MemoryThreatScanner.scan(replacement)
        if (scanError != null) return failure(scanError)

        return withLock {
            val current = readEntriesUnlocked()
            val matches = current.withIndex().filter { it.value.contains(needle) }
            when (matches.size) {
                0 -> failure(
                    "No entry contains '${needle.take(60)}'. Read the current entries first.",
                    current,
                )
                1 -> {
                    val updated = current.toMutableList()
                    updated[matches.single().index] = replacement
                    val newSize = entriesToBlob(updated).length
                    if (newSize > charLimit) {
                        return@withLock failure(
                            "Replacement would push memory to $newSize/$charLimit chars.",
                            current,
                        )
                    }
                    writeEntriesUnlocked(updated)
                    Result(true, "Entry replaced.", updated, newSize)
                }
                else -> failure(
                    "'${needle.take(40)}' matches ${matches.size} entries — pick a more specific substring.",
                    current,
                )
            }
        }
    }

    fun remove(oldText: String): Result {
        val needle = oldText.trim()
        if (needle.isEmpty()) return failure("oldText cannot be empty.")
        return withLock {
            val current = readEntriesUnlocked()
            val matches = current.withIndex().filter { it.value.contains(needle) }
            when (matches.size) {
                0 -> failure(
                    "No entry contains '${needle.take(60)}'.",
                    current,
                )
                1 -> {
                    val updated = current.toMutableList().also { it.removeAt(matches.single().index) }
                    writeEntriesUnlocked(updated)
                    Result(true, "Entry removed.", updated, entriesToBlob(updated).length)
                }
                else -> failure(
                    "'${needle.take(40)}' matches ${matches.size} entries — pick a more specific substring.",
                    current,
                )
            }
        }
    }

    fun clear() {
        withLock { writeEntriesUnlocked(emptyList()) }
    }

    /**
     * Replace the entire entry list atomically. Used by background
     * maintenance jobs (see
     * [com.prlancas.droidal.memory.learning.workers.MemoryTidyWorker])
     * that need to drop / rewrite many rows at once without re-walking
     * the on-insert duplicate-coverage logic for every survivor.
     *
     * The replacement still passes the [MemoryThreatScanner] and the
     * char-limit check; an oversize or scanner-rejected payload returns
     * `false` and leaves the file untouched. Empty input is equivalent
     * to [clear].
     */
    fun rewrite(entries: List<String>): Boolean {
        val cleaned = entries.map { it.trim() }.filter { it.isNotEmpty() }
        for (e in cleaned) {
            val scanError = MemoryThreatScanner.scan(e)
            if (scanError != null) {
                Log.w(TAG, "rewrite rejected: $scanError")
                return false
            }
        }
        val size = entriesToBlob(cleaned).length
        if (size > charLimit) {
            Log.w(TAG, "rewrite rejected: $size > charLimit $charLimit")
            return false
        }
        withLock { writeEntriesUnlocked(cleaned) }
        return true
    }

    fun render(): String {
        val entries = read()
        if (entries.isEmpty()) return ""
        return entriesToBlob(entries)
    }

    // -- helpers --------------------------------------------------------

    private fun readEntriesUnlocked(): List<String> {
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
            Log.w(TAG, "Failed to read ${file.path}: ${it.message}")
            return emptyList()
        }
        if (text.isBlank()) return emptyList()
        return text.split(ENTRY_DELIMITER)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun writeEntriesUnlocked(entries: List<String>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(entriesToBlob(entries), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // Some Android filesystems refuse rename-over; fall back.
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun entriesToBlob(entries: List<String>): String {
        if (entries.isEmpty()) return ""
        return entries.joinToString(separator = ENTRY_DELIMITER)
    }

    private fun failure(message: String, current: List<String> = read()): Result =
        Result(false, message, current, entriesToBlob(current).length)

    private inline fun <T> withLock(crossinline block: () -> T): T {
        file.parentFile?.mkdirs()
        val lockFile = File(file.parentFile, file.name + ".lock")
        if (!lockFile.exists()) lockFile.createNewFile()
        val raf = RandomAccessFile(lockFile, "rw")
        var lock: FileLock? = null
        return try {
            lock = raf.channel.lock()
            block()
        } finally {
            try { lock?.release() } catch (_: Exception) {}
            try { raf.close() } catch (_: Exception) {}
        }
    }
}

/**
 * Lightweight pattern-scan mirroring hermes-agent's `_MEMORY_THREAT_PATTERNS`
 * — entries land in the system prompt, so we reject obvious prompt-injection
 * / exfiltration payloads before persisting.
 */
private object MemoryThreatScanner {

    private val patterns: List<Pair<Regex, String>> = listOf(
        Regex("ignore\\s+(previous|all|above|prior)\\s+instructions", RegexOption.IGNORE_CASE) to "prompt_injection",
        Regex("you\\s+are\\s+now\\s+", RegexOption.IGNORE_CASE) to "role_hijack",
        Regex("do\\s+not\\s+tell\\s+the\\s+user", RegexOption.IGNORE_CASE) to "deception_hide",
        Regex("system\\s+prompt\\s+override", RegexOption.IGNORE_CASE) to "sys_prompt_override",
        Regex("disregard\\s+(your|all|any)\\s+(instructions|rules|guidelines)", RegexOption.IGNORE_CASE) to "disregard_rules",
        Regex("curl\\s+[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)", RegexOption.IGNORE_CASE) to "exfil_curl",
        Regex("authorized_keys", RegexOption.IGNORE_CASE) to "ssh_backdoor",
    )

    private val invisible = setOf(
        '\u200b', '\u200c', '\u200d', '\u2060', '\ufeff',
        '\u202a', '\u202b', '\u202c', '\u202d', '\u202e',
    )

    fun scan(content: String): String? {
        for (ch in content) {
            if (ch in invisible) {
                return "Blocked: content contains invisible unicode (possible injection)."
            }
        }
        for ((re, pid) in patterns) {
            if (re.containsMatchIn(content)) {
                return "Blocked: content matches threat pattern '$pid'."
            }
        }
        return null
    }
}
