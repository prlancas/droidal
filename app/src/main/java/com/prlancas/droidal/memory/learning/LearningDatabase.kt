package com.prlancas.droidal.memory.learning

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Plain `SQLiteOpenHelper`-backed persistence for the learning loop.
 *
 * Tables:
 * - `conversation_turn` — one row per user/assistant turn, scoped by userId + sessionId.
 * - `conversation_turn_fts` — FTS4 virtual table mirroring `text` for the
 *    `searchMemory` LLM tool.
 * - `news_item` — entries the news scout wants to surface to a user.
 * - `curator_state` — per-user `lastReflectedAt` / `lastNewsScoutAt` so workers
 *    can short-circuit when there's nothing new.
 *
 * Why plain SQLite (not Room): keeps the project free of KSP / KAPT setup
 * for a single-table-per-concern store, and matches the FTS5/4 pattern
 * hermes-agent uses in [tools/session_search_tool.py].
 */
class LearningDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "learning.db"

        /**
         * v2 added the spatial object memory (`object_landmark`, its FTS mirror,
         * and `object_alias`) for the "go to the cooker" feature. The upgrade is
         * additive — [onUpgrade] only creates the new tables so existing
         * conversation / news / curator data survives.
         */
        const val DB_VERSION = 2

        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        const val TBL_TURN = "conversation_turn"
        const val TBL_TURN_FTS = "conversation_turn_fts"
        const val TBL_NEWS = "news_item"
        const val TBL_CURATOR = "curator_state"
        const val TBL_OBJECT = "object_landmark"
        const val TBL_OBJECT_FTS = "object_landmark_fts"
        const val TBL_OBJECT_ALIAS = "object_alias"

        @Volatile private var instance: LearningDatabase? = null

        fun get(context: Context): LearningDatabase =
            instance ?: synchronized(this) {
                instance ?: LearningDatabase(context.applicationContext).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TBL_TURN (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                userId TEXT NOT NULL,
                sessionId TEXT NOT NULL,
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_turn_user_session ON $TBL_TURN(userId, sessionId, createdAt)")
        db.execSQL("CREATE INDEX idx_turn_created ON $TBL_TURN(createdAt)")

        db.execSQL(
            """
            CREATE VIRTUAL TABLE $TBL_TURN_FTS USING fts4(
                content=`$TBL_TURN`, text, userId
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER trg_turn_ai AFTER INSERT ON $TBL_TURN BEGIN
                INSERT INTO $TBL_TURN_FTS(docid, text, userId)
                VALUES (new.id, new.text, new.userId);
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER trg_turn_ad AFTER DELETE ON $TBL_TURN BEGIN
                DELETE FROM $TBL_TURN_FTS WHERE docid = old.id;
            END
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE $TBL_NEWS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                userId TEXT NOT NULL,
                interest TEXT NOT NULL,
                title TEXT NOT NULL,
                snippet TEXT NOT NULL,
                url TEXT NOT NULL,
                fetchedAt INTEGER NOT NULL,
                presentedAt INTEGER,
                UNIQUE(userId, url)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_news_user_pending ON $TBL_NEWS(userId, presentedAt, fetchedAt)")

        db.execSQL(
            """
            CREATE TABLE $TBL_CURATOR (
                userId TEXT PRIMARY KEY,
                lastReflectedAt INTEGER,
                lastNewsScoutAt INTEGER,
                lastProactiveAt INTEGER,
                paused INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )

        createObjectTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Versioned, additive migrations — do NOT drop existing user data.
        if (oldVersion < 2) {
            createObjectTables(db)
        }
    }

    /**
     * Spatial object memory (schema v2). `object_landmark` is one row per thing
     * Droidal has seen and localised on the map; `object_landmark_fts` mirrors
     * its text for the object search tool; `object_alias` maps synonyms
     * ("cooker") back to the canonical noun ("oven") so "go to the cooker"
     * resolves. Follows the same external-content FTS4 + triggers pattern as
     * the conversation log above.
     */
    private fun createObjectTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TBL_OBJECT (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL UNIQUE,
                userId TEXT NOT NULL,
                canonical TEXT NOT NULL,
                label TEXT NOT NULL,
                aliases TEXT NOT NULL,
                worldX REAL NOT NULL,
                worldY REAL NOT NULL,
                sourceX REAL NOT NULL,
                sourceY REAL NOT NULL,
                sourceYaw REAL NOT NULL,
                confidence REAL NOT NULL,
                isDoor INTEGER NOT NULL DEFAULT 0,
                thumbPath TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_object_user_canon ON $TBL_OBJECT(userId, canonical)")

        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS $TBL_OBJECT_FTS USING fts4(
                content=`$TBL_OBJECT`, canonical, label, aliases, userId
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS trg_object_ai AFTER INSERT ON $TBL_OBJECT BEGIN
                INSERT INTO $TBL_OBJECT_FTS(docid, canonical, label, aliases, userId)
                VALUES (new.id, new.canonical, new.label, new.aliases, new.userId);
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS trg_object_ad AFTER DELETE ON $TBL_OBJECT BEGIN
                DELETE FROM $TBL_OBJECT_FTS WHERE docid = old.id;
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS trg_object_au AFTER UPDATE ON $TBL_OBJECT BEGIN
                DELETE FROM $TBL_OBJECT_FTS WHERE docid = old.id;
                INSERT INTO $TBL_OBJECT_FTS(docid, canonical, label, aliases, userId)
                VALUES (new.id, new.canonical, new.label, new.aliases, new.userId);
            END
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TBL_OBJECT_ALIAS (
                userId TEXT NOT NULL,
                canonical TEXT NOT NULL,
                alias TEXT NOT NULL,
                UNIQUE(userId, canonical, alias)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_object_alias ON $TBL_OBJECT_ALIAS(userId, alias)")
    }
}

// ---------------------------------------------------------------------------
// Data classes returned from the DAO layer
// ---------------------------------------------------------------------------

data class ConversationTurn(
    val id: Long,
    val userId: String,
    val sessionId: String,
    val role: String,
    val text: String,
    val createdAt: Long,
)

data class NewsItem(
    val id: Long,
    val userId: String,
    val interest: String,
    val title: String,
    val snippet: String,
    val url: String,
    val fetchedAt: Long,
    val presentedAt: Long?,
)

data class CuratorState(
    val userId: String,
    val lastReflectedAt: Long?,
    val lastNewsScoutAt: Long?,
    val lastProactiveAt: Long?,
    val paused: Boolean,
)

data class TurnSearchHit(
    val id: Long,
    val userId: String,
    val sessionId: String,
    val role: String,
    val text: String,
    val createdAt: Long,
    val snippet: String,
)

data class SessionSummary(
    val sessionId: String,
    val userId: String,
    val firstAt: Long,
    val lastAt: Long,
    val turnCount: Int,
)

/**
 * One object Droidal has seen and localised on the SLAM map.
 *
 * [worldX]/[worldY] are the object's estimated position in the `map` frame;
 * [sourceX]/[sourceY]/[sourceYaw] are the robot pose the observation was taken
 * from (used as the navigation vantage point and to refine the estimate later).
 */
data class ObjectLandmark(
    val id: Long,
    val uuid: String,
    val userId: String,
    val canonical: String,
    val label: String,
    val aliases: List<String>,
    val worldX: Double,
    val worldY: Double,
    val sourceX: Double,
    val sourceY: Double,
    val sourceYaw: Double,
    val confidence: Double,
    val isDoor: Boolean,
    val thumbPath: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

class ConversationDao(private val helper: LearningDatabase) {

    fun insertTurn(userId: String, sessionId: String, role: String, text: String, createdAt: Long = System.currentTimeMillis()): Long {
        val cv = ContentValues().apply {
            put("userId", userId)
            put("sessionId", sessionId)
            put("role", role)
            put("text", text)
            put("createdAt", createdAt)
        }
        return helper.writableDatabase.insert(LearningDatabase.TBL_TURN, null, cv)
    }

    fun recentTurns(userId: String, limit: Int = 50): List<ConversationTurn> {
        val db = helper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, userId, sessionId, role, text, createdAt FROM ${LearningDatabase.TBL_TURN} " +
                "WHERE userId = ? ORDER BY createdAt DESC LIMIT ?",
            arrayOf(userId, limit.toString()),
        )
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ConversationTurn(
                            id = c.getLong(0),
                            userId = c.getString(1),
                            sessionId = c.getString(2),
                            role = c.getString(3),
                            text = c.getString(4),
                            createdAt = c.getLong(5),
                        ),
                    )
                }
            }
        }
    }

    fun turnsSince(userId: String, sinceMs: Long, limit: Int = 200): List<ConversationTurn> {
        val cursor = helper.readableDatabase.rawQuery(
            "SELECT id, userId, sessionId, role, text, createdAt FROM ${LearningDatabase.TBL_TURN} " +
                "WHERE userId = ? AND createdAt > ? ORDER BY createdAt ASC LIMIT ?",
            arrayOf(userId, sinceMs.toString(), limit.toString()),
        )
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ConversationTurn(
                            id = c.getLong(0),
                            userId = c.getString(1),
                            sessionId = c.getString(2),
                            role = c.getString(3),
                            text = c.getString(4),
                            createdAt = c.getLong(5),
                        ),
                    )
                }
            }
        }
    }

    fun sessions(userId: String, limit: Int = 50): List<SessionSummary> {
        val cursor = helper.readableDatabase.rawQuery(
            """
            SELECT sessionId, userId, MIN(createdAt), MAX(createdAt), COUNT(*)
            FROM ${LearningDatabase.TBL_TURN}
            WHERE userId = ?
            GROUP BY sessionId
            ORDER BY MAX(createdAt) DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(userId, limit.toString()),
        )
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        SessionSummary(
                            sessionId = c.getString(0),
                            userId = c.getString(1),
                            firstAt = c.getLong(2),
                            lastAt = c.getLong(3),
                            turnCount = c.getInt(4),
                        ),
                    )
                }
            }
        }
    }

    fun turnsForSession(sessionId: String): List<ConversationTurn> {
        val cursor = helper.readableDatabase.rawQuery(
            "SELECT id, userId, sessionId, role, text, createdAt FROM ${LearningDatabase.TBL_TURN} " +
                "WHERE sessionId = ? ORDER BY createdAt ASC",
            arrayOf(sessionId),
        )
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ConversationTurn(
                            id = c.getLong(0),
                            userId = c.getString(1),
                            sessionId = c.getString(2),
                            role = c.getString(3),
                            text = c.getString(4),
                            createdAt = c.getLong(5),
                        ),
                    )
                }
            }
        }
    }

    fun search(userId: String, query: String, limit: Int = 20): List<TurnSearchHit> {
        val match = sanitizeFtsQuery(query) ?: return emptyList()
        val cursor = helper.readableDatabase.rawQuery(
            """
            SELECT t.id, t.userId, t.sessionId, t.role, t.text, t.createdAt,
                   snippet(${LearningDatabase.TBL_TURN_FTS}, '[', ']', '...', -1, 16)
            FROM ${LearningDatabase.TBL_TURN_FTS} f
            JOIN ${LearningDatabase.TBL_TURN} t ON t.id = f.docid
            WHERE f.${LearningDatabase.TBL_TURN_FTS} MATCH ? AND t.userId = ?
            ORDER BY t.createdAt DESC LIMIT ?
            """.trimIndent(),
            arrayOf(match, userId, limit.toString()),
        )
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        TurnSearchHit(
                            id = c.getLong(0),
                            userId = c.getString(1),
                            sessionId = c.getString(2),
                            role = c.getString(3),
                            text = c.getString(4),
                            createdAt = c.getLong(5),
                            snippet = c.getString(6) ?: "",
                        ),
                    )
                }
            }
        }
    }

    fun deleteForUser(userId: String) {
        helper.writableDatabase.delete(LearningDatabase.TBL_TURN, "userId = ?", arrayOf(userId))
    }

    fun renameUser(oldId: String, newId: String) {
        if (oldId == newId) return
        val cv = ContentValues().apply { put("userId", newId) }
        helper.writableDatabase.update(LearningDatabase.TBL_TURN, cv, "userId = ?", arrayOf(oldId))
    }

    fun deleteSession(sessionId: String) {
        helper.writableDatabase.delete(LearningDatabase.TBL_TURN, "sessionId = ?", arrayOf(sessionId))
    }

    fun deleteAll() {
        helper.writableDatabase.delete(LearningDatabase.TBL_TURN, null, null)
    }

    /**
     * FTS4 needs MATCH to be a non-empty token list; passing user input
     * directly would let punctuation crash the query, so we strip to safe
     * chars and OR the resulting tokens.
     */
    private fun sanitizeFtsQuery(raw: String): String? {
        val tokens = raw.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(' ')
            .filter { it.isNotBlank() && it.length >= 2 }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(separator = " OR ") { "$it*" }
    }
}

class NewsDao(private val helper: LearningDatabase) {

    /** Insert if not present (uses UNIQUE(userId, url)). Returns true if inserted. */
    fun upsert(userId: String, interest: String, title: String, snippet: String, url: String): Boolean {
        val cv = ContentValues().apply {
            put("userId", userId)
            put("interest", interest)
            put("title", title)
            put("snippet", snippet)
            put("url", url)
            put("fetchedAt", System.currentTimeMillis())
        }
        val rowId = helper.writableDatabase.insertWithOnConflict(
            LearningDatabase.TBL_NEWS, null, cv, SQLiteDatabase.CONFLICT_IGNORE,
        )
        return rowId != -1L
    }

    fun pendingFor(userId: String, limit: Int = 5): List<NewsItem> {
        val cursor = helper.readableDatabase.rawQuery(
            """
            SELECT id, userId, interest, title, snippet, url, fetchedAt, presentedAt
            FROM ${LearningDatabase.TBL_NEWS}
            WHERE userId = ? AND presentedAt IS NULL
            ORDER BY fetchedAt DESC LIMIT ?
            """.trimIndent(),
            arrayOf(userId, limit.toString()),
        )
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(toItem(c))
                }
            }
        }
    }

    fun allFor(userId: String, limit: Int = 50): List<NewsItem> {
        val cursor = helper.readableDatabase.rawQuery(
            """
            SELECT id, userId, interest, title, snippet, url, fetchedAt, presentedAt
            FROM ${LearningDatabase.TBL_NEWS}
            WHERE userId = ?
            ORDER BY fetchedAt DESC LIMIT ?
            """.trimIndent(),
            arrayOf(userId, limit.toString()),
        )
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(toItem(c))
                }
            }
        }
    }

    fun markPresented(id: Long) {
        val cv = ContentValues().apply { put("presentedAt", System.currentTimeMillis()) }
        helper.writableDatabase.update(LearningDatabase.TBL_NEWS, cv, "id = ?", arrayOf(id.toString()))
    }

    /**
     * Drop a single news row by primary key. Returns true if a row was
     * actually deleted. Used by the Learning settings UI when the user
     * removes a story they're not interested in so the scout doesn't
     * keep resurfacing it (the UNIQUE(userId, url) constraint stops a
     * re-fetch from re-inserting it within the same scout window).
     */
    fun deleteById(id: Long): Boolean {
        val n = helper.writableDatabase.delete(
            LearningDatabase.TBL_NEWS,
            "id = ?",
            arrayOf(id.toString()),
        )
        return n > 0
    }

    fun deleteForUser(userId: String) {
        helper.writableDatabase.delete(LearningDatabase.TBL_NEWS, "userId = ?", arrayOf(userId))
    }

    /**
     * Re-key news rows from [oldId] to [newId]. Any rows that would
     * collide on the UNIQUE(userId, url) constraint with rows already
     * filed under [newId] are dropped — the rename UI guards against
     * this by refusing targets that already exist, so this fallback is
     * defensive only.
     */
    fun renameUser(oldId: String, newId: String) {
        if (oldId == newId) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(
                LearningDatabase.TBL_NEWS,
                "userId = ? AND url IN (SELECT url FROM ${LearningDatabase.TBL_NEWS} WHERE userId = ?)",
                arrayOf(oldId, newId),
            )
            val cv = ContentValues().apply { put("userId", newId) }
            db.update(LearningDatabase.TBL_NEWS, cv, "userId = ?", arrayOf(oldId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteAll() {
        helper.writableDatabase.delete(LearningDatabase.TBL_NEWS, null, null)
    }

    private fun toItem(c: android.database.Cursor): NewsItem = NewsItem(
        id = c.getLong(0),
        userId = c.getString(1),
        interest = c.getString(2),
        title = c.getString(3),
        snippet = c.getString(4),
        url = c.getString(5),
        fetchedAt = c.getLong(6),
        presentedAt = if (c.isNull(7)) null else c.getLong(7),
    )
}

class CuratorStateDao(private val helper: LearningDatabase) {

    fun get(userId: String): CuratorState {
        val cursor = helper.readableDatabase.rawQuery(
            "SELECT userId, lastReflectedAt, lastNewsScoutAt, lastProactiveAt, paused " +
                "FROM ${LearningDatabase.TBL_CURATOR} WHERE userId = ?",
            arrayOf(userId),
        )
        return cursor.use { c ->
            if (c.moveToNext()) {
                CuratorState(
                    userId = c.getString(0),
                    lastReflectedAt = if (c.isNull(1)) null else c.getLong(1),
                    lastNewsScoutAt = if (c.isNull(2)) null else c.getLong(2),
                    lastProactiveAt = if (c.isNull(3)) null else c.getLong(3),
                    paused = c.getInt(4) != 0,
                )
            } else {
                CuratorState(userId, null, null, null, false)
            }
        }
    }

    fun setReflected(userId: String, atMs: Long) = upsert(userId) { put("lastReflectedAt", atMs) }
    fun setScouted(userId: String, atMs: Long) = upsert(userId) { put("lastNewsScoutAt", atMs) }
    fun setProactive(userId: String, atMs: Long) = upsert(userId) { put("lastProactiveAt", atMs) }

    fun deleteForUser(userId: String) {
        helper.writableDatabase.delete(LearningDatabase.TBL_CURATOR, "userId = ?", arrayOf(userId))
    }

    /**
     * Re-key curator state rows from [oldId] to [newId]. Drops any
     * pre-existing row for [newId] first to keep the userId-as-PK
     * invariant.
     */
    fun renameUser(oldId: String, newId: String) {
        if (oldId == newId) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(LearningDatabase.TBL_CURATOR, "userId = ?", arrayOf(newId))
            val cv = ContentValues().apply { put("userId", newId) }
            db.update(LearningDatabase.TBL_CURATOR, cv, "userId = ?", arrayOf(oldId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteAll() {
        helper.writableDatabase.delete(LearningDatabase.TBL_CURATOR, null, null)
    }

    private inline fun upsert(userId: String, block: ContentValues.() -> Unit) {
        val db = helper.writableDatabase
        val updateValues = ContentValues().apply(block)
        val updated = db.update(
            LearningDatabase.TBL_CURATOR,
            updateValues,
            "userId = ?",
            arrayOf(userId),
        )
        if (updated == 0) {
            val insertValues = ContentValues().apply {
                put("userId", userId)
                put("paused", 0)
                block()
            }
            db.insert(LearningDatabase.TBL_CURATOR, null, insertValues)
        }
    }
}

/**
 * DAO for the spatial object memory ([LearningDatabase.TBL_OBJECT] +
 * [LearningDatabase.TBL_OBJECT_ALIAS]).
 *
 * Landmarks are deduplicated: a new observation of the same canonical noun
 * within [MERGE_DIST_M] of an existing one updates that row (refreshing pose,
 * confidence and thumbnail) rather than piling up duplicates as Droidal drives
 * back and forth past the same object.
 */
class ObjectDao(private val helper: LearningDatabase) : ObjectLookup {

    /**
     * Insert a new landmark, or merge into a nearby existing one with the same
     * canonical. Also records the aliases. Returns the row's `uuid`.
     */
    @Suppress("LongParameterList")
    fun upsertObject(
        userId: String,
        canonical: String,
        label: String,
        aliases: List<String>,
        worldX: Double,
        worldY: Double,
        sourceX: Double,
        sourceY: Double,
        sourceYaw: Double,
        confidence: Double,
        isDoor: Boolean,
        thumbPath: String?,
        now: Long = System.currentTimeMillis(),
    ): String {
        val db = helper.writableDatabase
        val canon = canonical.trim().lowercase()
        val aliasCsv = aliases.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
        db.beginTransaction()
        try {
            val existing = nearestSameCanonical(userId, canon, worldX, worldY)
            val uuid: String
            if (existing != null) {
                uuid = existing.uuid
                val cv = ContentValues().apply {
                    put("label", label)
                    put("aliases", aliasCsv.joinToString(","))
                    put("worldX", worldX)
                    put("worldY", worldY)
                    put("sourceX", sourceX)
                    put("sourceY", sourceY)
                    put("sourceYaw", sourceYaw)
                    put("confidence", maxOf(existing.confidence, confidence))
                    put("isDoor", if (isDoor) 1 else 0)
                    if (thumbPath != null) put("thumbPath", thumbPath)
                    put("updatedAt", now)
                }
                db.update(LearningDatabase.TBL_OBJECT, cv, "uuid = ?", arrayOf(uuid))
            } else {
                uuid = java.util.UUID.randomUUID().toString()
                val cv = ContentValues().apply {
                    put("uuid", uuid)
                    put("userId", userId)
                    put("canonical", canon)
                    put("label", label)
                    put("aliases", aliasCsv.joinToString(","))
                    put("worldX", worldX)
                    put("worldY", worldY)
                    put("sourceX", sourceX)
                    put("sourceY", sourceY)
                    put("sourceYaw", sourceYaw)
                    put("confidence", confidence)
                    put("isDoor", if (isDoor) 1 else 0)
                    put("thumbPath", thumbPath)
                    put("createdAt", now)
                    put("updatedAt", now)
                }
                db.insert(LearningDatabase.TBL_OBJECT, null, cv)
            }
            addAliasesInternal(db, userId, canon, aliasCsv)
            db.setTransactionSuccessful()
            return uuid
        } finally {
            db.endTransaction()
        }
    }

    private fun addAliasesInternal(
        db: SQLiteDatabase,
        userId: String,
        canonical: String,
        aliases: List<String>,
    ) {
        for (alias in aliases) {
            if (alias == canonical) continue
            val cv = ContentValues().apply {
                put("userId", userId)
                put("canonical", canonical)
                put("alias", alias)
            }
            db.insertWithOnConflict(
                LearningDatabase.TBL_OBJECT_ALIAS,
                null,
                cv,
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
    }

    private fun nearestSameCanonical(
        userId: String,
        canonical: String,
        x: Double,
        y: Double,
    ): ObjectLandmark? =
        byCanonical(userId, canonical)
            .minByOrNull { kotlin.math.hypot(it.worldX - x, it.worldY - y) }
            ?.takeIf { kotlin.math.hypot(it.worldX - x, it.worldY - y) <= MERGE_DIST_M }

    /** Point an existing landmark row at its saved thumbnail file. */
    fun updateThumbPath(uuid: String, thumbPath: String) {
        val cv = ContentValues().apply { put("thumbPath", thumbPath) }
        helper.writableDatabase.update(LearningDatabase.TBL_OBJECT, cv, "uuid = ?", arrayOf(uuid))
    }

    /** Canonical nouns a spoken [term] could refer to (alias table + self). */
    override fun canonicalsForAlias(userId: String, term: String): List<String> {
        val t = term.trim().lowercase()
        if (t.isEmpty()) return emptyList()
        val cursor = helper.readableDatabase.rawQuery(
            "SELECT DISTINCT canonical FROM ${LearningDatabase.TBL_OBJECT_ALIAS} " +
                "WHERE userId = ? AND alias = ?",
            arrayOf(userId, t),
        )
        val result = cursor.use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }.toMutableList()
        if (t !in result) result.add(0, t)
        return result
    }

    override fun byCanonical(userId: String, canonical: String, limit: Int): List<ObjectLandmark> =
        query(
            "userId = ? AND canonical = ?",
            arrayOf(userId, canonical.trim().lowercase()),
            "confidence DESC, updatedAt DESC",
            limit,
        )

    fun all(userId: String, limit: Int = 200): List<ObjectLandmark> =
        query("userId = ?", arrayOf(userId), "updatedAt DESC", limit)

    fun doors(userId: String, limit: Int = 50): List<ObjectLandmark> =
        query("userId = ? AND isDoor = 1", arrayOf(userId), "updatedAt DESC", limit)

    /** Full-text search over canonical + label + aliases for the object tools. */
    override fun search(userId: String, rawQuery: String, limit: Int): List<ObjectLandmark> {
        val match = sanitizeFtsQuery(rawQuery) ?: return emptyList()
        val cursor = helper.readableDatabase.rawQuery(
            """
            SELECT o.id, o.uuid, o.userId, o.canonical, o.label, o.aliases,
                   o.worldX, o.worldY, o.sourceX, o.sourceY, o.sourceYaw,
                   o.confidence, o.isDoor, o.thumbPath, o.createdAt, o.updatedAt
            FROM ${LearningDatabase.TBL_OBJECT_FTS} f
            JOIN ${LearningDatabase.TBL_OBJECT} o ON o.id = f.docid
            WHERE f.${LearningDatabase.TBL_OBJECT_FTS} MATCH ? AND o.userId = ?
            ORDER BY o.confidence DESC, o.updatedAt DESC LIMIT ?
            """.trimIndent(),
            arrayOf(match, userId, limit.toString()),
        )
        return cursor.use { c -> buildList { while (c.moveToNext()) add(toLandmark(c)) } }
    }

    private fun query(
        where: String,
        args: Array<String>,
        orderBy: String,
        limit: Int,
    ): List<ObjectLandmark> {
        val cursor = helper.readableDatabase.rawQuery(
            """
            SELECT id, uuid, userId, canonical, label, aliases,
                   worldX, worldY, sourceX, sourceY, sourceYaw,
                   confidence, isDoor, thumbPath, createdAt, updatedAt
            FROM ${LearningDatabase.TBL_OBJECT}
            WHERE $where ORDER BY $orderBy LIMIT ?
            """.trimIndent(),
            args + limit.toString(),
        )
        return cursor.use { c -> buildList { while (c.moveToNext()) add(toLandmark(c)) } }
    }

    fun deleteForUser(userId: String) {
        val db = helper.writableDatabase
        db.delete(LearningDatabase.TBL_OBJECT, "userId = ?", arrayOf(userId))
        db.delete(LearningDatabase.TBL_OBJECT_ALIAS, "userId = ?", arrayOf(userId))
    }

    fun renameUser(oldId: String, newId: String) {
        if (oldId == newId) return
        val db = helper.writableDatabase
        val cv = ContentValues().apply { put("userId", newId) }
        db.update(LearningDatabase.TBL_OBJECT, cv, "userId = ?", arrayOf(oldId))
        db.update(LearningDatabase.TBL_OBJECT_ALIAS, cv, "userId = ?", arrayOf(oldId))
    }

    fun deleteAll() {
        val db = helper.writableDatabase
        db.delete(LearningDatabase.TBL_OBJECT, null, null)
        db.delete(LearningDatabase.TBL_OBJECT_ALIAS, null, null)
    }

    private fun toLandmark(c: android.database.Cursor): ObjectLandmark = ObjectLandmark(
        id = c.getLong(0),
        uuid = c.getString(1),
        userId = c.getString(2),
        canonical = c.getString(3),
        label = c.getString(4),
        aliases = c.getString(5).split(",").map { it.trim() }.filter { it.isNotBlank() },
        worldX = c.getDouble(6),
        worldY = c.getDouble(7),
        sourceX = c.getDouble(8),
        sourceY = c.getDouble(9),
        sourceYaw = c.getDouble(10),
        confidence = c.getDouble(11),
        isDoor = c.getInt(12) != 0,
        thumbPath = if (c.isNull(13)) null else c.getString(13),
        createdAt = c.getLong(14),
        updatedAt = c.getLong(15),
    )

    private fun sanitizeFtsQuery(raw: String): String? {
        val tokens = raw.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(' ')
            .filter { it.isNotBlank() && it.length >= 2 }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(separator = " OR ") { "$it*" }
    }

    companion object {
        /** New observations within this many metres of a same-canonical landmark merge into it. */
        const val MERGE_DIST_M = 1.0
    }
}
