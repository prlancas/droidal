package com.prlancas.droidal.memory.learning

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [LearningPaths.sanitize].
 *
 * The sanitiser is the only thing that prevents user-supplied display
 * names ending up as filesystem paths or SQL keys with arbitrary
 * characters. It also picks the storage bucket every other learning
 * tool resolves against, so a regression here would silently scatter
 * memory across multiple folders.
 *
 * The path/file helpers themselves are thin wrappers around `File` and
 * are exercised indirectly via the higher-level stores; here we focus
 * on the sanitisation contract.
 */
class LearningPathsTest {

    @Test
    fun `sanitize lowercases letters`() {
        assertEquals("paul", LearningPaths.sanitize("Paul"))
    }

    @Test
    fun `sanitize collapses internal whitespace into hyphens`() {
        assertEquals("paul-lancaster", LearningPaths.sanitize("Paul Lancaster"))
    }

    @Test
    fun `sanitize keeps existing hyphens`() {
        assertEquals("paul-l", LearningPaths.sanitize("Paul-L"))
    }

    @Test
    fun `sanitize maps underscores to hyphens`() {
        assertEquals("paul-l", LearningPaths.sanitize("Paul_L"))
    }

    @Test
    fun `sanitize strips punctuation that is neither letter digit nor allowed separator`() {
        assertEquals("dr-droidal", LearningPaths.sanitize("Dr. Droidal!"))
    }

    @Test
    fun `sanitize keeps digits`() {
        assertEquals("user42", LearningPaths.sanitize("User42"))
    }

    @Test
    fun `sanitize trims leading and trailing hyphens`() {
        assertEquals("paul", LearningPaths.sanitize("--Paul--"))
    }

    @Test
    fun `sanitize empty string falls back to UNKNOWN_USER`() {
        assertEquals(LearningPaths.UNKNOWN_USER, LearningPaths.sanitize(""))
    }

    @Test
    fun `sanitize whitespace-only falls back to UNKNOWN_USER`() {
        assertEquals(LearningPaths.UNKNOWN_USER, LearningPaths.sanitize("   "))
    }

    @Test
    fun `sanitize all-symbols falls back to UNKNOWN_USER`() {
        // After mapping, "!@#$" leaves no characters; trim of leading
        // / trailing hyphens leaves an empty string, so we fall back
        // to the unknown bucket rather than persisting an empty path.
        assertEquals(LearningPaths.UNKNOWN_USER, LearningPaths.sanitize("!@#\$"))
    }

    @Test
    fun `sanitize keeps unicode letters (matches Character isLetterOrDigit)`() {
        // The implementation uses Kotlin's `isLetterOrDigit`, which is
        // Unicode-aware and considers e.g. 'æ', 'ø' as letters.
        // Pinning this so a future change is a deliberate decision —
        // moving to ASCII-only would silently re-bucket existing
        // diacritic-bearing user IDs.
        assertEquals("æø", LearningPaths.sanitize("Æø"))
    }

    @Test
    fun `sanitize is idempotent`() {
        val once = LearningPaths.sanitize("Paul Lancaster")
        val twice = LearningPaths.sanitize(once)
        assertEquals(once, twice)
    }
}
