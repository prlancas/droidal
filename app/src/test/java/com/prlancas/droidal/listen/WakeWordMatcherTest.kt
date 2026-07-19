package com.prlancas.droidal.listen

import com.prlancas.droidal.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the wake-decision policy that replaced Porcupine.
 *
 * The three ways to wake — always-on override, a visible face, and the
 * wake-word regex — are all load-bearing for whether Droidal responds at
 * all, so each path (and the "don't wake on ambient chatter" negative
 * case) is pinned here.
 */
class WakeWordMatcherTest {

    private fun default(alwaysTrigger: Boolean = false) =
        WakeWordMatcher.build(SettingsRepository.DEFAULT_WAKE_REGEX, alwaysTrigger)

    @Test
    fun `bare wake word wakes and forwards the whole utterance`() {
        val decision = default().evaluate("droidal", faceVisible = false)
        assertTrue(decision.wake)
        assertEquals("droidal", decision.message)
    }

    @Test
    fun `wake word mid sentence forwards the full trimmed transcript`() {
        val decision = default().evaluate("  Hey Droidal, what's the weather?  ", faceVisible = false)
        assertTrue(decision.wake)
        assertEquals("Hey Droidal, what's the weather?", decision.message)
    }

    @Test
    fun `default regex is case-insensitive`() {
        assertTrue(default().evaluate("DROIDAL", faceVisible = false).wake)
    }

    @Test
    fun `non-matching speech with no face is ignored`() {
        val decision = default().evaluate("what time is it", faceVisible = false)
        assertFalse(decision.wake)
        assertEquals("", decision.message)
    }

    @Test
    fun `word boundary stops android from matching droid`() {
        assertFalse(default().evaluate("my android phone", faceVisible = false).wake)
    }

    @Test
    fun `a visible face wakes even without the wake word`() {
        val decision = default().evaluate("what time is it", faceVisible = true)
        assertTrue(decision.wake)
        assertEquals("what time is it", decision.message)
    }

    @Test
    fun `always-trigger wakes on any non-blank speech`() {
        val matcher = default(alwaysTrigger = true)
        val decision = matcher.evaluate("random muttering", faceVisible = false)
        assertTrue(decision.wake)
        assertEquals("random muttering", decision.message)
    }

    @Test
    fun `blank transcript never wakes, even with always-trigger and a face`() {
        val matcher = default(alwaysTrigger = true)
        assertFalse(matcher.evaluate("   ", faceVisible = true).wake)
    }

    @Test
    fun `invalid regex falls back to the default pattern`() {
        // "(" is an unterminated group — build() should swallow it and use
        // the default so a bad user pattern can't disable waking entirely.
        val matcher = WakeWordMatcher.build("(unclosed", alwaysTrigger = false)
        assertTrue(matcher.evaluate("hey there", faceVisible = false).wake)
    }
}
