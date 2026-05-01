package com.prlancas.droidal.speech

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [MarkdownStripper].
 *
 * The stripper is the last thing the LLM's reply touches before it
 * reaches TextToSpeech, so any markdown markers that leak through are
 * read out loud as words ("asterisk asterisk hello asterisk asterisk").
 * These tests pin the existing transformations so future changes don't
 * accidentally start speaking punctuation.
 *
 * Particular care is taken to keep prose punctuation (apostrophes, full
 * stops, commas) intact — the [TtsStreamer] sentence splitter relies
 * on them to find chunk boundaries.
 */
class MarkdownStripperTest {

    @Test
    fun `empty input returns empty output`() {
        assertEquals("", MarkdownStripper.forSpeech(""))
    }

    @Test
    fun `plain prose is left alone aside from trim`() {
        assertEquals(
            "Hello, how are you?",
            MarkdownStripper.forSpeech("  Hello, how are you?  "),
        )
    }

    @Test
    fun `bold double-star markers are removed but content kept`() {
        assertEquals(
            "Hello world",
            MarkdownStripper.forSpeech("**Hello** world"),
        )
    }

    @Test
    fun `bold double-underscore markers are removed but content kept`() {
        assertEquals(
            "Hello world",
            MarkdownStripper.forSpeech("__Hello__ world"),
        )
    }

    @Test
    fun `italic single-star markers are removed but content kept`() {
        assertEquals(
            "Hello world",
            MarkdownStripper.forSpeech("*Hello* world"),
        )
    }

    @Test
    fun `italic single-underscore markers are removed but content kept`() {
        assertEquals(
            "Hello world",
            MarkdownStripper.forSpeech("_Hello_ world"),
        )
    }

    @Test
    fun `inline code backticks are removed but content kept`() {
        assertEquals(
            "Use the foo function.",
            MarkdownStripper.forSpeech("Use the `foo` function."),
        )
    }

    @Test
    fun `fenced code blocks are dropped wholesale`() {
        val input = "Before\n```kotlin\nval x = 1\n```\nAfter"
        val result = MarkdownStripper.forSpeech(input)
        // The fenced block is replaced with a single space; the
        // surrounding newlines stay so prose paragraphs are still
        // separated. Pinning the actual behaviour rather than aiming
        // for "no whitespace at all" — the TTS engine collapses
        // adjacent whitespace at speak time.
        assertEquals("Before\n \nAfter", result)
    }

    @Test
    fun `markdown links keep label and drop URL`() {
        assertEquals(
            "See the docs for details.",
            MarkdownStripper.forSpeech("See [the docs](https://example.com) for details."),
        )
    }

    @Test
    fun `images are dropped wholesale`() {
        assertEquals(
            "Below: text.",
            MarkdownStripper.forSpeech("Below: ![alt text](https://example.com/x.png) text."),
        )
    }

    @Test
    fun `headings are stripped of leading hashes`() {
        assertEquals(
            "Title\nbody text",
            MarkdownStripper.forSpeech("## Title\nbody text"),
        )
    }

    @Test
    fun `bullet lists become plain lines`() {
        val input = "- one\n- two\n- three"
        val result = MarkdownStripper.forSpeech(input)
        assertEquals("one\ntwo\nthree", result)
    }

    @Test
    fun `numbered lists become plain lines`() {
        val input = "1. one\n2. two\n3. three"
        val result = MarkdownStripper.forSpeech(input)
        assertEquals("one\ntwo\nthree", result)
    }

    @Test
    fun `section sign is converted to sentence-ending dot-space`() {
        // The section sign is the in-prompt entry delimiter for memory
        // / skill blocks. Speaking it back as a literal "section sign"
        // would be jarring; we replace it with sentence-ending
        // punctuation so the streamer flushes a chunk on the boundary.
        val input = "First entry\n§\nSecond entry"
        assertEquals("First entry. Second entry", MarkdownStripper.forSpeech(input))
    }

    @Test
    fun `apostrophes are preserved`() {
        assertEquals(
            "It's Droidal's birthday.",
            MarkdownStripper.forSpeech("It's Droidal's birthday."),
        )
    }

    @Test
    fun `multiple spaces collapse but single newlines survive`() {
        assertEquals(
            "Hello world\nstill here",
            MarkdownStripper.forSpeech("Hello   world\nstill here"),
        )
    }

    @Test
    fun `striked text loses the markers but keeps content`() {
        assertEquals(
            "removed",
            MarkdownStripper.forSpeech("~~removed~~"),
        )
    }

    @Test
    fun `horizontal rule lines vanish leaving a blank line`() {
        // The rule itself is removed but the surrounding newlines stay
        // — the spoken result has a blank line where the rule was. TTS
        // engines treat blank lines as a slightly longer pause, which
        // is what we want for a section break.
        val input = "before\n---\nafter"
        val result = MarkdownStripper.forSpeech(input)
        assertEquals("before\n\nafter", result)
    }

    @Test
    fun `combination of markers is fully cleaned`() {
        val input = "## Hello\n\n**bold** and *italic* with `code` and [link](https://x).\n- item"
        val result = MarkdownStripper.forSpeech(input)
        assertEquals(
            "Hello\n\nbold and italic with code and link.\nitem",
            result,
        )
    }
}
