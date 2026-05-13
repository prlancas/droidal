package com.prlancas.droidal.memory.learning

/**
 * Deterministic, LLM-free pass that tightens a list of memory entries.
 *
 * The goal is "information density" — Droidal's MEMORY.md / USER.md
 * land verbatim in the system prompt every turn, so duplicate or
 * near-duplicate entries waste tokens. The reflector worker tries to
 * keep this under control on insertion (see
 * [MarkdownStore.add]'s coverage check), but over time the LLM still
 * paraphrases existing facts and ends up with overlapping rows that
 * the on-insert check can't catch retrospectively.
 *
 * This pass:
 *
 *   1. Collapses exact normalised duplicates (case + punctuation
 *      folded via [MarkdownStore.normaliseForCompare]).
 *   2. Drops any entry whose normalised form is a substring of another
 *      entry's normalised form — the broader entry wins. Iterates in
 *      length-descending order so transitive coverage (A ⊃ B ⊃ C)
 *      collapses to just A in one pass.
 *
 * The result preserves the order of the surviving entries from the
 * original list (so a UI listing them post-tidy stays stable for the
 * user).
 *
 * Returned alongside the tightened list is the count of entries
 * dropped — used by [com.prlancas.droidal.memory.learning.workers.MemoryTidyWorker]
 * to decide whether a write-back is worth doing.
 */
object MemoryTidier {

    data class Tidied(val entries: List<String>, val droppedCount: Int)

    fun tidy(entries: List<String>): Tidied {
        if (entries.size <= 1) return Tidied(entries, 0)

        // Pre-compute the normalised form once per entry — used for
        // both the duplicate fold and the coverage check.
        val pairs = entries.map { it to MarkdownStore.normaliseForCompare(it) }

        // Skip empty-after-normalise entries entirely — they're noise.
        val candidates = pairs.filter { it.second.isNotEmpty() }
        if (candidates.size != pairs.size) {
            return tidyAfterEmptyDrop(candidates, pairs.size - candidates.size)
        }
        return tidyAfterEmptyDrop(candidates, 0)
    }

    private fun tidyAfterEmptyDrop(
        candidates: List<Pair<String, String>>,
        emptyDropped: Int,
    ): Tidied {
        // First pass — fold exact normalised duplicates. Keep the first
        // occurrence so order is preserved.
        val seenNormals = HashSet<String>()
        val dedup = mutableListOf<Pair<String, String>>()
        var dupDropped = 0
        for (pair in candidates) {
            if (seenNormals.add(pair.second)) {
                dedup += pair
            } else {
                dupDropped++
            }
        }

        if (dedup.size <= 1) {
            return Tidied(dedup.map { it.first }, emptyDropped + dupDropped)
        }

        // Second pass — drop any entry whose normalised form is a
        // proper substring of a longer entry's normalised form. Walk
        // longest-norm first so the broadest entry wins all coverage
        // checks; we mark "dropped" indices into a Set keyed on the
        // original dedup list so the order of survivors matches the
        // original ordering when we collect them at the end.
        val sortedByLen = dedup.withIndex()
            .sortedByDescending { it.value.second.length }
        val dropped = HashSet<Int>()
        for ((iIdx, iPair) in sortedByLen.withIndex()) {
            if (iPair.index in dropped) continue
            val iNorm = iPair.value.second
            // Inner sweep done as filter+forEach so detekt's
            // LoopWithTooManyJumpStatements rule stays happy — the
            // alternative was two `continue` statements inside a
            // for-loop, which it flags as harder to read.
            sortedByLen
                .subList(iIdx + 1, sortedByLen.size)
                .filter { jPair ->
                    jPair.index !in dropped &&
                        jPair.value.second.length < iNorm.length &&
                        iNorm.contains(jPair.value.second)
                }
                .forEach { jPair -> dropped += jPair.index }
        }

        val survivors = dedup
            .filterIndexed { index, _ -> index !in dropped }
            .map { it.first }
        return Tidied(
            entries = survivors,
            droppedCount = emptyDropped + dupDropped + dropped.size,
        )
    }
}
