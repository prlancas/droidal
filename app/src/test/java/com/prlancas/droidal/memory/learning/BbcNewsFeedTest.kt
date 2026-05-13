package com.prlancas.droidal.memory.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BbcNewsFeed] — the RSS replacement for the old
 * DuckDuckGo-driven news scout.
 *
 * The parser must:
 *   - Pull title / link / description out of every `<item>` block.
 *   - Strip the BBC's `?at_medium=RSS&at_campaign=rss` tracking query
 *     so the URL the LLM sees stays short (this was the original
 *     reason DDG results blew the context — they brought huge tracking
 *     URLs in tow).
 *   - Survive items with empty descriptions.
 *   - Return an empty list on blank input (the fetch path silently
 *     swallows network errors and the worker must not crash).
 *
 * The matcher must:
 *   - Case-insensitively substring-match each interest against
 *     `title + description`.
 *   - Return at most one match per item, even when several interests
 *     hit the same headline.
 *   - Stamp the matched interest onto the result so the news row in
 *     the Learning UI can show why it surfaced.
 */
class BbcNewsFeedTest {

    @Test
    fun `parse extracts items from a representative BBC RSS payload`() {
        val items = BbcNewsFeed.parse(SAMPLE_RSS)

        assertEquals(3, items.size)
        assertEquals(
            "Chris Mason: Starmer hopes to save his job with promise of change - and warnings of chaos",
            items[0].title,
        )
        // Tracking query has been stripped — this is the whole point.
        assertEquals(
            "https://www.bbc.com/news/articles/c392djn3zzdo",
            items[0].link,
        )
        assertTrue(
            "description should mention 'next 24 hours'",
            items[0].description.contains("next 24 hours"),
        )
    }

    @Test
    fun `parse trims and accepts items without a description`() {
        val items = BbcNewsFeed.parse(ITEM_WITHOUT_DESCRIPTION)
        assertEquals(1, items.size)
        assertEquals("Headline only", items[0].title)
        assertEquals("https://www.bbc.com/news/articles/abc", items[0].link)
        assertEquals("", items[0].description)
    }

    @Test
    fun `parse returns empty list for blank input`() {
        assertEquals(emptyList<BbcNewsFeed.Item>(), BbcNewsFeed.parse(""))
        assertEquals(emptyList<BbcNewsFeed.Item>(), BbcNewsFeed.parse("   "))
    }

    @Test
    fun `parse skips items missing a link`() {
        val noLink = """
            <rss><channel>
              <item>
                <title><![CDATA[Has title, no link]]></title>
              </item>
            </channel></rss>
        """.trimIndent()
        assertEquals(emptyList<BbcNewsFeed.Item>(), BbcNewsFeed.parse(noLink))
    }

    @Test
    fun `stripTrackingParams removes BBC's at_medium tail`() {
        assertEquals(
            "https://www.bbc.com/news/articles/c392djn3zzdo",
            BbcNewsFeed.stripTrackingParams(
                "https://www.bbc.com/news/articles/c392djn3zzdo?at_medium=RSS&at_campaign=rss",
            ),
        )
    }

    @Test
    fun `stripTrackingParams leaves links without a query untouched`() {
        assertEquals(
            "https://www.bbc.com/news/articles/c392djn3zzdo",
            BbcNewsFeed.stripTrackingParams("https://www.bbc.com/news/articles/c392djn3zzdo"),
        )
    }

    @Test
    fun `matchInterests surfaces a headline that mentions an interest`() {
        val items = BbcNewsFeed.parse(SAMPLE_RSS)
        val matches = BbcNewsFeed.matchInterests(items, listOf("Brighton"))
        assertEquals(1, matches.size)
        assertEquals("Brighton", matches[0].interest)
        assertTrue(matches[0].item.title.contains("Brighton"))
    }

    @Test
    fun `matchInterests is case-insensitive`() {
        val items = BbcNewsFeed.parse(SAMPLE_RSS)
        val matches = BbcNewsFeed.matchInterests(items, listOf("starmer"))
        assertEquals(1, matches.size)
        // The first interest token (not its original casing) is what
        // gets stamped, but the headline is the same regardless.
        assertTrue(matches[0].item.title.lowercase().contains("starmer"))
    }

    @Test
    fun `matchInterests returns each item at most once even with multiple hits`() {
        val items = BbcNewsFeed.parse(SAMPLE_RSS)
        // "Starmer" and "promise" both appear in the same headline.
        val matches = BbcNewsFeed.matchInterests(items, listOf("Starmer", "promise"))
        // Still a single Match for that item; the first interest wins
        // the `interest` stamp.
        val starmerMatches = matches.filter {
            it.item.title.contains("Starmer", ignoreCase = true)
        }
        assertEquals(1, starmerMatches.size)
        assertEquals("Starmer", starmerMatches.single().interest)
    }

    @Test
    fun `matchInterests drops short interest tokens to avoid spurious hits`() {
        val items = BbcNewsFeed.parse(SAMPLE_RSS)
        // Two-character interest tokens are rejected — they would hit
        // half the headlines by accident.
        val matches = BbcNewsFeed.matchInterests(items, listOf("of"))
        assertEquals(emptyList<BbcNewsFeed.Match>(), matches)
    }

    @Test
    fun `matchInterests returns empty list when interests do not match anything`() {
        val items = BbcNewsFeed.parse(SAMPLE_RSS)
        val matches = BbcNewsFeed.matchInterests(items, listOf("woodworking"))
        assertEquals(emptyList<BbcNewsFeed.Match>(), matches)
    }

    @Test
    fun `matchInterests returns empty list when given no items`() {
        val matches = BbcNewsFeed.matchInterests(emptyList(), listOf("anything"))
        assertEquals(emptyList<BbcNewsFeed.Match>(), matches)
    }

    @Test
    fun `description is truncated to keep the system prompt lean`() {
        // The truncation cap is a private constant; this test pins
        // that an oversized description doesn't pass through unchanged.
        val veryLong = "A".repeat(1_000)
        val xml = """
            <rss><channel>
              <item>
                <title><![CDATA[Long body item]]></title>
                <link>https://www.bbc.com/news/articles/long</link>
                <description><![CDATA[$veryLong]]></description>
              </item>
            </channel></rss>
        """.trimIndent()
        val items = BbcNewsFeed.parse(xml)
        assertEquals(1, items.size)
        assertTrue(
            "Description should be truncated below the original length (${items[0].description.length})",
            items[0].description.length < veryLong.length,
        )
    }

    @Test
    fun `Item data class equality survives identical content`() {
        // Sanity check on the data-class equality contract — we rely
        // on it when deduplicating matches by link.
        val a = BbcNewsFeed.Item("t", "https://x", "d")
        val b = BbcNewsFeed.Item("t", "https://x", "d")
        val c = BbcNewsFeed.Item("t", "https://y", "d")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    companion object {
        /**
         * Three-item subset of the live feed captured 2026-05-13.
         * Kept inline so the test runs offline and the format is
         * pinned even if the BBC reshape their wrapper tags.
         */
        private val SAMPLE_RSS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title><![CDATA[BBC News]]></title>
                <item>
                  <title><![CDATA[Chris Mason: Starmer hopes to save his job with promise of change - and warnings of chaos]]></title>
                  <description><![CDATA[There is a big moment coming in the next 24 hours, for Sir Keir Starmer and for would-be challengers to the prime minister.]]></description>
                  <link>https://www.bbc.com/news/articles/c392djn3zzdo?at_medium=RSS&amp;at_campaign=rss</link>
                </item>
                <item>
                  <title><![CDATA[Met Police prepares armoured vehicles and 4,000 officers for dual London protests]]></title>
                  <description><![CDATA[It comes as a Unite the Kingdom rally is taking place in central London on the same day as the annual Nakba march.]]></description>
                  <link>https://www.bbc.com/news/articles/c172n5e0prko?at_medium=RSS&amp;at_campaign=rss</link>
                </item>
                <item>
                  <title><![CDATA[Bodies of three young women pulled from sea off Brighton]]></title>
                  <description><![CDATA[Police leave the scene and Brighton beach reopens after the bodies of three women are found.]]></description>
                  <link>https://www.bbc.com/news/articles/c0e29n94ze4o?at_medium=RSS&amp;at_campaign=rss</link>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        private val ITEM_WITHOUT_DESCRIPTION = """
            <rss><channel>
              <item>
                <title><![CDATA[Headline only]]></title>
                <link>https://www.bbc.com/news/articles/abc</link>
              </item>
            </channel></rss>
        """.trimIndent()
    }
}
