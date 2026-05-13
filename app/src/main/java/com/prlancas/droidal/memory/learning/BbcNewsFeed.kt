package com.prlancas.droidal.memory.learning

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pulls headlines from the BBC News RSS feed and matches them against
 * the user's declared interests.
 *
 * Why a curated RSS source instead of an open web search:
 *   The previous DuckDuckGo-driven scout returned ad-laden book listings
 *   and other commercial fluff with sprawling tracking URLs. Those URLs
 *   ballooned the system prompt and tripped context-overflow errors on
 *   the local LLM. The BBC feed is short (typically 30-50 entries),
 *   editorially curated, and the link form is a single canonical
 *   `bbc.com/news/articles/<id>` URL once we drop the
 *   `?at_medium=RSS&at_campaign=rss` query string the feed appends.
 *
 * Matching is intentionally simple — case-insensitive substring on the
 * headline + description against each interest token. RSS is small
 * enough that fetching the whole feed once per scout run and filtering
 * client-side is cheaper than spinning up a search API for every
 * interest.
 */
object BbcNewsFeed {

    private const val TAG = "BbcNewsFeed"
    private const val FEED_URL = "https://feeds.bbci.co.uk/news/rss.xml?edition=uk"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Droidal) AppleWebKit/537.36 (KHTML, like Gecko)"
    private const val TIMEOUT_MS = 10_000
    private const val MAX_DESCRIPTION_CHARS = 240

    private val ITEM_RE = Regex(
        "<item>([\\s\\S]*?)</item>",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Matches `<title>...</title>` and captures the inner value as
     * either a CDATA section (`cdata` group) or plain text (`plain`
     * group). Same pattern below for `<link>` and `<description>`.
     * Tag is non-recursive (BBC feed never nests these in an item).
     */
    private val TITLE_RE = Regex(
        "<title>\\s*(?:<!\\[CDATA\\[(?<cdata>[\\s\\S]*?)\\]\\]>|(?<plain>[\\s\\S]*?))\\s*</title>",
        RegexOption.IGNORE_CASE,
    )
    private val LINK_RE = Regex(
        "<link>\\s*(?:<!\\[CDATA\\[(?<cdata>[\\s\\S]*?)\\]\\]>|(?<plain>[\\s\\S]*?))\\s*</link>",
        RegexOption.IGNORE_CASE,
    )
    private val DESCRIPTION_RE = Regex(
        "<description>\\s*(?:<!\\[CDATA\\[(?<cdata>[\\s\\S]*?)\\]\\]>|(?<plain>[\\s\\S]*?))\\s*</description>",
        RegexOption.IGNORE_CASE,
    )

    data class Item(val title: String, val link: String, val description: String)

    /**
     * Fetch and parse the BBC News UK feed.
     *
     * Returns an empty list on any network / parse failure — callers
     * (see [com.prlancas.droidal.memory.learning.workers.NewsScoutWorker])
     * already log "no results" when this happens and surface the
     * silence as a `ConversationLog` info entry.
     */
    suspend fun fetch(): List<Item> = withContext(Dispatchers.IO) {
        val xml = runCatching { downloadFeed() }
            .onFailure { Log.w(TAG, "Feed fetch failed: ${it.message}") }
            .getOrNull()
            ?: return@withContext emptyList()
        runCatching { parse(xml) }
            .onFailure { Log.w(TAG, "Feed parse failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /**
     * Filter [items] to those whose title or description mention any of
     * [interests] (case-insensitive substring). Each item is returned at
     * most once even if it matches several interests; the first
     * matching interest is recorded so the news row can show the user
     * why we surfaced it.
     */
    fun matchInterests(items: List<Item>, interests: List<String>): List<Match> {
        if (items.isEmpty() || interests.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val out = mutableListOf<Match>()
        for (item in items) {
            val haystack = (item.title + " " + item.description).lowercase()
            val hit = interests.firstOrNull { token ->
                val needle = token.trim().lowercase()
                needle.length >= 3 && haystack.contains(needle)
            } ?: continue
            if (seen.add(item.link)) {
                out += Match(item = item, interest = hit)
            }
        }
        return out
    }

    data class Match(val item: Item, val interest: String)

    private fun downloadFeed(): String {
        val conn = (URL(FEED_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/rss+xml, application/xml;q=0.9, */*;q=0.8")
            setRequestProperty("Accept-Language", "en-GB,en;q=0.7")
        }
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                Log.w(TAG, "BBC RSS HTTP ${conn.responseCode}")
                ""
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Regex-driven parse over the RSS payload.
     *
     * RSS from the BBC has a stable shape: each headline lives in a
     * `<item>...</item>` block containing `<title>`, `<link>` and
     * `<description>` children (and optional media metadata we don't
     * care about). The titles and descriptions are wrapped in CDATA
     * sections; the regex extracts either CDATA contents or plain
     * inner text.
     *
     * A bespoke regex parser keeps the code Android-API-free so the
     * full path is unit-testable on the JVM (no Robolectric required).
     * Internal visibility is preserved so the test class in the same
     * package can hit [parse] directly with a recorded feed sample.
     */
    internal fun parse(xml: String): List<Item> {
        if (xml.isBlank()) return emptyList()
        return ITEM_RE.findAll(xml)
            .mapNotNull { match ->
                val body = match.groupValues[1]
                val title = readField(body, TITLE_RE) ?: return@mapNotNull null
                val link = readField(body, LINK_RE) ?: return@mapNotNull null
                val description = readField(body, DESCRIPTION_RE).orEmpty()
                if (title.isBlank() || link.isBlank()) return@mapNotNull null
                Item(
                    title = title,
                    link = stripTrackingParams(link),
                    description = description.take(MAX_DESCRIPTION_CHARS),
                )
            }
            .toList()
    }

    private fun readField(body: String, re: Regex): String? {
        val m = re.find(body) ?: return null
        val cdata = m.groups["cdata"]?.value
        val plain = m.groups["plain"]?.value
        val raw = cdata ?: plain ?: return null
        return raw.trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Drop the `?at_medium=RSS&at_campaign=rss` query string the BBC
     * appends to every link. Keeps URLs short enough that the system
     * prompt isn't overwhelmed by query params when we list a few
     * pending stories — the canonical article path is fine on its own.
     */
    internal fun stripTrackingParams(rawLink: String): String {
        val qIndex = rawLink.indexOf('?')
        return if (qIndex >= 0) rawLink.substring(0, qIndex) else rawLink
    }
}
