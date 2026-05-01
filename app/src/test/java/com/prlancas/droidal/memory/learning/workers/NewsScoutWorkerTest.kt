package com.prlancas.droidal.memory.learning.workers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NewsScoutWorker.extractInterests] — the heuristic that
 * seeds the background news scout's web searches.
 *
 * The user-visible bug this guards against: Droidal records facts like
 * "Paul likes climbing" in MEMORY.md long before they get curated into
 * USER.md. If the scout only mined USER.md it would log
 * `no interests extracted from profile` even when there were obvious
 * interest hints in memory. These tests pin the multi-source extraction
 * so a future refactor can't quietly regress to profile-only.
 */
class NewsScoutWorkerTest {

    @Test
    fun `extracts interest from profile only`() {
        val profile = "Paul is interested in climbing."
        val interests = NewsScoutWorker.extractInterests(profile)
        assertEquals(listOf("climbing"), interests)
    }

    @Test
    fun `extracts interest from memory only when profile is blank`() {
        val memory = "Paul likes black coffee."
        val interests = NewsScoutWorker.extractInterests("", memory)
        assertEquals(listOf("black coffee"), interests)
    }

    @Test
    fun `extracts interests from both sources without duplicates`() {
        val profile = "Likes climbing."
        val memory = "Likes climbing.\n§\nFan of Arsenal."
        val interests = NewsScoutWorker.extractInterests(profile, memory)
        assertEquals(listOf("climbing", "Arsenal"), interests)
    }

    @Test
    fun `handles all blank sources`() {
        val interests = NewsScoutWorker.extractInterests("", "   ", "\n")
        assertTrue(interests.isEmpty())
    }

    @Test
    fun `splits comma and 'and' separated interests`() {
        val memory = "Loves rock climbing, bouldering and trad routes."
        val interests = NewsScoutWorker.extractInterests("", memory)
        assertEquals(listOf("rock climbing", "bouldering", "trad routes"), interests)
    }

    @Test
    fun `recognises multiple lead-in phrases`() {
        val memory = """
            Paul is into woodworking.
            §
            Enjoys long walks.
            §
            Fan of jazz.
        """.trimIndent()
        val interests = NewsScoutWorker.extractInterests("", memory)
        assertEquals(listOf("woodworking", "long walks", "jazz"), interests)
    }
}
