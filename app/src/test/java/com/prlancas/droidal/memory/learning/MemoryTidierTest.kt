package com.prlancas.droidal.memory.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the deterministic dedupe pass run by
 * [com.prlancas.droidal.memory.learning.workers.MemoryTidyWorker].
 *
 * Behaviour pinned here:
 *  - Exact-normalised duplicates collapse to a single entry, order
 *    of the first occurrence is preserved.
 *  - An entry whose normalised form is a substring of a longer
 *    entry's normalised form is dropped (the broader entry wins).
 *  - Comparison uses [MarkdownStore.normaliseForCompare], so
 *    punctuation, casing, and run-length whitespace differences
 *    don't keep otherwise-redundant entries alive.
 *  - The pass is a strict cleanup — it never invents new content.
 *
 * Plain-old-JVM logic, so no Android shims required.
 */
class MemoryTidierTest {

    @Test
    fun `empty input returns empty result`() {
        val tidied = MemoryTidier.tidy(emptyList())
        assertEquals(emptyList<String>(), tidied.entries)
        assertEquals(0, tidied.droppedCount)
    }

    @Test
    fun `single entry is preserved unchanged`() {
        val tidied = MemoryTidier.tidy(listOf("Paul likes climbing."))
        assertEquals(listOf("Paul likes climbing."), tidied.entries)
        assertEquals(0, tidied.droppedCount)
    }

    @Test
    fun `exact duplicates collapse to the first occurrence`() {
        val tidied = MemoryTidier.tidy(
            listOf(
                "Paul likes climbing.",
                "Paul likes climbing.",
                "Paul likes climbing.",
            ),
        )
        assertEquals(listOf("Paul likes climbing."), tidied.entries)
        assertEquals(2, tidied.droppedCount)
    }

    @Test
    fun `case and punctuation differences are treated as duplicates`() {
        val tidied = MemoryTidier.tidy(
            listOf(
                "Paul likes climbing.",
                "paul likes climbing",
                "PAUL  LIKES  CLIMBING!!",
            ),
        )
        // First occurrence wins for the survivor, but the dropped
        // count covers the rephrasings.
        assertEquals(listOf("Paul likes climbing."), tidied.entries)
        assertEquals(2, tidied.droppedCount)
    }

    @Test
    fun `entries fully contained in a longer entry are dropped`() {
        val tidied = MemoryTidier.tidy(
            listOf(
                "Paul likes climbing.",
                "Paul likes climbing on weekends in the Peak District.",
            ),
        )
        // The narrower entry is folded into the broader one.
        assertEquals(
            listOf("Paul likes climbing on weekends in the Peak District."),
            tidied.entries,
        )
        assertEquals(1, tidied.droppedCount)
    }

    @Test
    fun `transitive coverage collapses to a single entry in one pass`() {
        val tidied = MemoryTidier.tidy(
            listOf(
                "climbing",
                "likes climbing",
                "Paul likes climbing on weekends",
            ),
        )
        assertEquals(
            listOf("Paul likes climbing on weekends"),
            tidied.entries,
        )
        assertEquals(2, tidied.droppedCount)
    }

    @Test
    fun `unrelated entries are preserved`() {
        val input = listOf(
            "Paul likes climbing.",
            "Sarah prefers cycling.",
            "Lives in Edinburgh.",
        )
        val tidied = MemoryTidier.tidy(input)
        assertEquals(input, tidied.entries)
        assertEquals(0, tidied.droppedCount)
    }

    @Test
    fun `blank entries are stripped`() {
        val tidied = MemoryTidier.tidy(
            listOf(
                "Paul likes climbing.",
                "   ",
                "",
                "\n\t",
            ),
        )
        assertEquals(listOf("Paul likes climbing."), tidied.entries)
        assertTrue(
            "Three blank entries were dropped, got dropped=${tidied.droppedCount}",
            tidied.droppedCount == 3,
        )
    }

    @Test
    fun `survivor order preserves first-occurrence order across the surviving set`() {
        // Coverage is strict substring (matches the semantics
        // [MarkdownStore.add] uses on insertion), so "owns a desktop
        // Linux box" is NOT covered by "owns a MacBook Pro and a
        // desktop Linux box" — the intervening words break the
        // substring. The test pins the two surviving entries and that
        // they appear in the original first-occurrence order.
        val tidied = MemoryTidier.tidy(
            listOf(
                "owns a MacBook Pro",
                "owns a desktop Linux box",
                "owns a MacBook Pro and a desktop Linux box",
                "owns a MacBook Pro",
            ),
        )
        assertEquals(
            listOf(
                "owns a desktop Linux box",
                "owns a MacBook Pro and a desktop Linux box",
            ),
            tidied.entries,
        )
        // 1 exact duplicate (the second "owns a MacBook Pro") + 1
        // covered ("owns a MacBook Pro" is a strict substring of the
        // long entry) = 2 dropped.
        assertEquals(2, tidied.droppedCount)
    }
}
