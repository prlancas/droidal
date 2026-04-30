package com.prlancas.droidal.memory.learning

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Minimal DuckDuckGo HTML search.
 *
 * Hits `https://html.duckduckgo.com/html/?q=…`, parses the lightweight
 * markup returned for terminal browsers, and returns the top N results.
 * No API key required — matches the pattern many self-host agents use
 * (e.g. AutoGPT's DuckDuckGo provider). The HTML shape is simple and has
 * been stable for years, but a single-file scraper makes it cheap to fix
 * if it changes.
 */
object WebSearch {

    private const val TAG = "WebSearch"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val ENDPOINT = "https://html.duckduckgo.com/html/?q="
    private const val TIMEOUT_MS = 10_000

    data class Result(val title: String, val url: String, val snippet: String)

    suspend fun search(query: String, max: Int = 5): List<Result> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList<Result>()
        val url = URL(ENDPOINT + URLEncoder.encode(query, "UTF-8"))
        val html = runCatching {
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Language", "en-GB,en;q=0.7")
                instanceFollowRedirects = true
            }.let { conn ->
                try {
                    if (conn.responseCode in 200..299) {
                        conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    } else {
                        Log.w(TAG, "DuckDuckGo HTTP ${conn.responseCode}")
                        ""
                    }
                } finally {
                    conn.disconnect()
                }
            }
        }.getOrElse {
            Log.w(TAG, "DDG fetch failed: ${it.message}")
            ""
        }
        if (html.isBlank()) return@withContext emptyList<Result>()
        parse(html, max)
    }

    /**
     * Crude regex-driven parse. DuckDuckGo's HTML page wraps each result in
     * a `<div class="result …">` block with three predictable inner pieces:
     *   1. `<a class="result__a" href="…">Title</a>`
     *   2. `<a class="result__snippet">snippet text</a>`
     * We extract all `result__a` anchors then pair them with the matching
     * snippets. Hrefs come back as `/l/?uddg=<url-encoded>` redirects on
     * the HTML endpoint — we unwrap those.
     */
    private fun parse(html: String, max: Int): List<Result> {
        val anchorRe = Regex(
            "<a[^>]*class=\"result__a\"[^>]*href=\"(?<href>[^\"]+)\"[^>]*>(?<title>[\\s\\S]*?)</a>",
            RegexOption.IGNORE_CASE,
        )
        val snippetRe = Regex(
            "<a[^>]*class=\"result__snippet\"[^>]*>(?<text>[\\s\\S]*?)</a>",
            RegexOption.IGNORE_CASE,
        )
        val anchors = anchorRe.findAll(html).toList()
        val snippets = snippetRe.findAll(html).map { stripTags(it.groups["text"]?.value.orEmpty()) }.toList()
        val out = mutableListOf<Result>()
        for ((idx, m) in anchors.withIndex()) {
            if (out.size >= max) break
            val rawHref = m.groups["href"]?.value.orEmpty()
            val title = stripTags(m.groups["title"]?.value.orEmpty())
            val unwrapped = unwrapRedirect(rawHref)
            if (title.isBlank() || unwrapped.isBlank()) continue
            val snippet = snippets.getOrNull(idx).orEmpty()
            out += Result(title, unwrapped, snippet)
        }
        return out
    }

    private fun unwrapRedirect(raw: String): String {
        if (raw.isBlank()) return raw
        // DDG HTML page wraps real URLs as /l/?uddg=<encoded>&rut=…
        val needle = "uddg="
        val idx = raw.indexOf(needle)
        if (idx < 0) return raw
        val tail = raw.substring(idx + needle.length)
        val end = tail.indexOf('&').let { if (it == -1) tail.length else it }
        return runCatching { URLDecoder.decode(tail.substring(0, end), "UTF-8") }
            .getOrElse { raw }
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<")
            .replace("&gt;", ">").trim()
}
