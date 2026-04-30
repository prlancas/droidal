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
        const val DB_VERSION = 1

        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        const val TBL_TURN = "conversation_turn"
        const val TBL_TURN_FTS = "conversation_turn_fts"
        const val TBL_NEWS = "news_item"
        const val TBL_CURATOR = "curator_state"

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Single-version schema right now; if we bump DB_VERSION later we can
        // add migration steps here. Drop+recreate is acceptable for the first
        // shipping release — no user data yet.
        db.execSQL("DROP TABLE IF EXISTS $TBL_TURN_FTS")
        db.execSQL("DROP TABLE IF EXISTS $TBL_TURN")
        db.execSQL("DROP TABLE IF EXISTS $TBL_NEWS")
        db.execSQL("DROP TABLE IF EXISTS $TBL_CURATOR")
        onCreate(db)
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

    fun deleteForUser(userId: String) {
        helper.writableDatabase.delete(LearningDatabase.TBL_NEWS, "userId = ?", arrayOf(userId))
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
