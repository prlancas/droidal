package com.prlancas.droidal.settings

import com.prlancas.droidal.settings.SettingsRepository.Companion.DEFAULT_LOCAL_MAX_NUM_TOKENS
import com.prlancas.droidal.settings.SettingsRepository.Companion.MAX_LOCAL_MAX_NUM_TOKENS
import com.prlancas.droidal.settings.SettingsRepository.Companion.MIN_LOCAL_MAX_NUM_TOKENS
import com.prlancas.droidal.settings.SettingsRepository.Companion.clampLocalMaxNumTokens
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the contract for the on-device engine's `maxNumTokens` setting:
 *
 *  - Default is 5000 — chosen so Gemma-4 / Gemma-3n loads on the 8 GB
 *    Samsung S23 GPU without OOMing at init. If anyone bumps this up
 *    in [SettingsRepository] without also bumping the test, that's a
 *    deliberate change worth catching in review.
 *  - Sentinel/zero values fall back to the default rather than passing
 *    `0` straight into `EngineConfig.maxNumTokens`.
 *  - Below-floor values are clamped up to [MIN_LOCAL_MAX_NUM_TOKENS]
 *    so we always have room for the system prompt + tool schemas.
 *  - Above-ceiling values (e.g. a user who tries to use the model's
 *    full 32K context window) are clamped down to
 *    [MAX_LOCAL_MAX_NUM_TOKENS] — beyond that we hit native aborts on
 *    the GPU backend before the fallback path runs.
 */
class SettingsRepositoryMaxNumTokensTest {

    @Test
    fun `default is 5000`() {
        assertEquals(5000, DEFAULT_LOCAL_MAX_NUM_TOKENS)
    }

    @Test
    fun `clamp returns default for sentinel zero`() {
        assertEquals(DEFAULT_LOCAL_MAX_NUM_TOKENS, clampLocalMaxNumTokens(0))
    }

    @Test
    fun `clamp returns default for negative values`() {
        assertEquals(DEFAULT_LOCAL_MAX_NUM_TOKENS, clampLocalMaxNumTokens(-1))
    }

    @Test
    fun `clamp lifts below-floor values to the minimum`() {
        assertEquals(MIN_LOCAL_MAX_NUM_TOKENS, clampLocalMaxNumTokens(1))
        assertEquals(MIN_LOCAL_MAX_NUM_TOKENS, clampLocalMaxNumTokens(MIN_LOCAL_MAX_NUM_TOKENS - 1))
    }

    @Test
    fun `clamp passes through values inside the supported range`() {
        assertEquals(MIN_LOCAL_MAX_NUM_TOKENS, clampLocalMaxNumTokens(MIN_LOCAL_MAX_NUM_TOKENS))
        assertEquals(DEFAULT_LOCAL_MAX_NUM_TOKENS, clampLocalMaxNumTokens(DEFAULT_LOCAL_MAX_NUM_TOKENS))
        assertEquals(8192, clampLocalMaxNumTokens(8192))
        assertEquals(MAX_LOCAL_MAX_NUM_TOKENS, clampLocalMaxNumTokens(MAX_LOCAL_MAX_NUM_TOKENS))
    }

    @Test
    fun `clamp pulls above-ceiling values back to the maximum`() {
        assertEquals(MAX_LOCAL_MAX_NUM_TOKENS, clampLocalMaxNumTokens(MAX_LOCAL_MAX_NUM_TOKENS + 1))
        assertEquals(MAX_LOCAL_MAX_NUM_TOKENS, clampLocalMaxNumTokens(Int.MAX_VALUE))
    }
}
