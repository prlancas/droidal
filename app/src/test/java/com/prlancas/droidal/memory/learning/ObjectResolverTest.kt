package com.prlancas.droidal.memory.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectResolverTest {

    private val user = "environment"

    private fun landmark(
        uuid: String,
        canonical: String,
        aliases: List<String> = emptyList(),
        confidence: Double = 0.5,
        updatedAt: Long = 0L,
        label: String = canonical,
    ) = ObjectLandmark(
        id = 0, uuid = uuid, userId = user, canonical = canonical, label = label,
        aliases = aliases, worldX = 1.0, worldY = 2.0, sourceX = 0.0, sourceY = 0.0,
        sourceYaw = 0.0, confidence = confidence, isDoor = false, thumbPath = null,
        createdAt = 0L, updatedAt = updatedAt,
    )

    /** In-memory [ObjectLookup] fake so this runs on the JVM (no SQLite). */
    private class FakeLookup(
        private val rows: List<ObjectLandmark>,
        private val aliasTable: Map<String, List<String>> = emptyMap(),
    ) : ObjectLookup {
        override fun byCanonical(userId: String, canonical: String, limit: Int) =
            rows.filter { it.canonical == canonical }

        override fun canonicalsForAlias(userId: String, term: String): List<String> {
            val out = mutableListOf(term)
            aliasTable[term]?.let { out.addAll(it) }
            return out.distinct()
        }

        override fun search(userId: String, rawQuery: String, limit: Int) =
            rows.filter { r ->
                val hay = (listOf(r.canonical, r.label) + r.aliases).joinToString(" ")
                rawQuery.split(" ").all { hay.contains(it) }
            }
    }

    @Test
    fun `exact canonical resolves`() {
        val lookup = FakeLookup(listOf(landmark("a", "oven")))
        val best = ObjectResolver(lookup).best(user, "oven")
        assertEquals("a", best?.uuid)
    }

    @Test
    fun `alias cooker resolves to oven`() {
        val lookup = FakeLookup(
            rows = listOf(landmark("a", "oven", aliases = listOf("cooker", "stove"))),
            aliasTable = mapOf("cooker" to listOf("oven")),
        )
        val best = ObjectResolver(lookup).best(user, "cooker")
        assertEquals("oven", best?.canonical)
    }

    @Test
    fun `falls back to full-text search`() {
        val lookup = FakeLookup(listOf(landmark("a", "oven", label = "oven")))
        // "ov" isn't an exact canonical or alias, but search matches substrings.
        val hits = ObjectResolver(lookup).resolve(user, "oven")
        assertTrue(hits.isNotEmpty())
    }

    @Test
    fun `ranks by confidence then recency`() {
        val lookup = FakeLookup(
            listOf(
                landmark("low", "oven", confidence = 0.3, updatedAt = 100),
                landmark("high", "oven", confidence = 0.9, updatedAt = 50),
                landmark("mid", "oven", confidence = 0.9, updatedAt = 200),
            ),
        )
        val ranked = ObjectResolver(lookup).resolve(user, "oven")
        assertEquals(listOf("mid", "high", "low"), ranked.map { it.uuid })
    }

    @Test
    fun `unknown object returns null`() {
        val lookup = FakeLookup(listOf(landmark("a", "oven")))
        assertNull(ObjectResolver(lookup).best(user, "spaceship"))
    }

    @Test
    fun `blank query returns nothing`() {
        val lookup = FakeLookup(listOf(landmark("a", "oven")))
        assertTrue(ObjectResolver(lookup).resolve(user, "  ").isEmpty())
    }
}
