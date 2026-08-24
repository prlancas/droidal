package com.prlancas.droidal.memory.learning

/**
 * Read-only slice of [ObjectDao] the resolver needs, extracted as an interface
 * so [ObjectResolver] can be covered by fast JVM unit tests with an in-memory
 * fake (the real DAO needs an Android SQLite database).
 */
interface ObjectLookup {
    fun byCanonical(userId: String, canonical: String, limit: Int = 20): List<ObjectLandmark>
    fun canonicalsForAlias(userId: String, term: String): List<String>
    fun search(userId: String, rawQuery: String, limit: Int = 20): List<ObjectLandmark>
}

/**
 * Turns a spoken object name ("the cooker") into stored [ObjectLandmark]s,
 * so "go to the cooker" can find the landmark the VLM filed under "oven".
 *
 * Resolution order (first non-empty wins), matching the plan:
 *  1. Exact canonical match ("oven" -> oven landmarks).
 *  2. Alias-table expansion ("cooker" -> oven, via the aliases the VLM
 *     volunteered when it saw the object).
 *  3. Full-text search over canonical + label + aliases (prefix match), for
 *     partial / fuzzy names.
 *
 * Results are ranked by confidence then recency so [best] returns the strongest,
 * most recent sighting.
 */
class ObjectResolver(private val lookup: ObjectLookup) {

    fun resolve(userId: String, name: String): List<ObjectLandmark> {
        val term = name.trim().lowercase()
        if (term.isEmpty()) return emptyList()

        lookup.byCanonical(userId, term).takeIf { it.isNotEmpty() }?.let { return rank(it) }

        val viaAlias = lookup.canonicalsForAlias(userId, term)
            .flatMap { lookup.byCanonical(userId, it) }
            .distinctBy { it.uuid }
        if (viaAlias.isNotEmpty()) return rank(viaAlias)

        return rank(lookup.search(userId, term).distinctBy { it.uuid })
    }

    fun best(userId: String, name: String): ObjectLandmark? = resolve(userId, name).firstOrNull()

    private fun rank(list: List<ObjectLandmark>): List<ObjectLandmark> =
        list.sortedWith(compareByDescending<ObjectLandmark> { it.confidence }.thenByDescending { it.updatedAt })
}
