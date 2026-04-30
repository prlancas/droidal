package com.prlancas.droidal.speech

/**
 * Markdown -> plain-text helper used right before [Speak] / TTS so the
 * synthesiser doesn't read out punctuation as words ("asterisk asterisk
 * hello asterisk asterisk").
 *
 * The LLM is also told (in the system prompt) to reply in plain spoken
 * English, but we still strip defensively because:
 *   - Local on-device models often slip into markdown anyway when
 *     summarising lists or showing emphasis.
 *   - Memory / skill blocks rendered through the settings "Speak" button
 *     are stored as `§`-delimited markdown.
 *
 * The stripper is intentionally conservative — it only removes the most
 * common markdown markers and leaves prose punctuation (apostrophes,
 * commas, sentence-end markers) alone so the TtsStreamer's sentence
 * splitter still works.
 */
object MarkdownStripper {

    private val FENCED_CODE = Regex("```[\\s\\S]*?```")
    private val INLINE_CODE = Regex("`([^`]*)`")
    private val BOLD_DOUBLE_STAR = Regex("\\*\\*(.+?)\\*\\*")
    private val BOLD_DOUBLE_UNDERSCORE = Regex("__(.+?)__")
    private val ITALIC_STAR = Regex("(?<!\\*)\\*(?!\\*)([^*]+?)(?<!\\*)\\*(?!\\*)")
    private val ITALIC_UNDERSCORE = Regex("(?<!_)_(?!_)([^_]+?)(?<!_)_(?!_)")
    private val STRIKE = Regex("~~(.+?)~~")
    private val LINK = Regex("\\[([^\\]]+)]\\([^)]+\\)")
    private val IMAGE = Regex("!\\[[^\\]]*]\\([^)]+\\)")
    private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+", RegexOption.MULTILINE)
    private val BLOCKQUOTE = Regex("^\\s{0,3}>+\\s?", RegexOption.MULTILINE)
    private val BULLET = Regex("^\\s{0,6}[-*+]\\s+", RegexOption.MULTILINE)
    private val ORDERED = Regex("^\\s{0,6}\\d+\\.\\s+", RegexOption.MULTILINE)
    private val HORIZONTAL = Regex("^\\s{0,3}([-*_])\\1{2,}\\s*$", RegexOption.MULTILINE)
    private val SECTION_SIGN = Regex("\\n?§\\n?")
    private val MULTIPLE_SPACES = Regex(" {2,}")
    private val MULTIPLE_NEWLINES = Regex("\\n{3,}")

    /**
     * Convert a markdown string into something a screen-reader can speak
     * naturally. Empty in / empty out is supported. The returned string
     * preserves sentence-ending punctuation so [TtsStreamer] can still
     * find chunk boundaries.
     */
    fun forSpeech(input: String): String {
        if (input.isEmpty()) return input

        var out = input
            .replace(FENCED_CODE, " ")
            .replace(IMAGE, " ")
        out = LINK.replace(out) { it.groupValues[1] }
        out = INLINE_CODE.replace(out) { it.groupValues[1] }
        out = BOLD_DOUBLE_STAR.replace(out) { it.groupValues[1] }
        out = BOLD_DOUBLE_UNDERSCORE.replace(out) { it.groupValues[1] }
        out = ITALIC_STAR.replace(out) { it.groupValues[1] }
        out = ITALIC_UNDERSCORE.replace(out) { it.groupValues[1] }
        out = STRIKE.replace(out) { it.groupValues[1] }

        out = out
            .replace(HORIZONTAL, "")
            .replace(HEADING, "")
            .replace(BLOCKQUOTE, "")
            .replace(BULLET, "")
            .replace(ORDERED, "")
            .replace(SECTION_SIGN, ". ")
            // Drop the bare characters that markdown leaves dangling
            // (e.g. an unmatched `*` or `_` from a stray emphasis).
            .replace(Regex("(^|\\s)[*_~](\\s|$)"), "$1$2")
            .replace(MULTIPLE_SPACES, " ")
            .replace(MULTIPLE_NEWLINES, "\n\n")
            .trim()

        return out
    }
}
