package com.prlancas.droidal.memory.learning

import android.content.Context
import com.prlancas.droidal.event.events.StartConversation
import com.prlancas.droidal.face.FaceStorage

/**
 * Top-level facade replacing the legacy `Memory` object. All learning
 * artefacts (memory entries, user-profile entries, skills, conversation
 * history) flow through this class, and everything is keyed on `userId`.
 *
 * Mirrors hermes-agent's `MemoryManager.build_system_prompt` / memory tool
 * surface area, scaled to a single in-process Android client.
 */
class LearningStore private constructor(private val context: Context) {

    companion object {
        const val MEMORY_CHAR_LIMIT = 2200
        const val USER_CHAR_LIMIT = 1375

        @Volatile private var instance: LearningStore? = null

        fun get(context: Context): LearningStore =
            instance ?: synchronized(this) {
                instance ?: LearningStore(context.applicationContext).also { instance = it }
            }

        const val TARGET_MEMORY = "memory"
        const val TARGET_USER = "user"
    }

    val db: LearningDatabase get() = LearningDatabase.get(context)
    val conversationDao = ConversationDao(db)
    val newsDao = NewsDao(db)
    val curatorStateDao = CuratorStateDao(db)

    fun memoryStore(userId: String): MarkdownStore =
        MarkdownStore(LearningPaths.memoryFile(context, userId), MEMORY_CHAR_LIMIT)

    fun userProfileStore(userId: String): MarkdownStore =
        MarkdownStore(LearningPaths.userProfileFile(context, userId), USER_CHAR_LIMIT)

    fun storeFor(userId: String, target: String): MarkdownStore = when (target.lowercase()) {
        TARGET_USER -> userProfileStore(userId)
        else -> memoryStore(userId)
    }

    fun skills(userId: String): SkillStore = SkillStore(context, userId)

    /**
     * Returns the system-prompt block hermes-style: header description,
     * frozen MEMORY snapshot, frozen USER snapshot, available skills index,
     * and a short summary of the most recent prior turns.
     */
    fun systemPromptBlock(startConversation: StartConversation): String {
        val userId = LearningPaths.sanitize(startConversation.user ?: LearningPaths.UNKNOWN_USER)
        val displayUser = startConversation.user?.takeIf { it.isNotBlank() } ?: "an unrecognised user"
        val nowLine = "The current time is ${java.time.LocalDateTime.now()}."

        val memoryBlock = renderBlock(
            heading = "MEMORY (your personal notes)",
            store = memoryStore(userId),
        )
        val userBlock = renderBlock(
            heading = "USER PROFILE ($displayUser)",
            store = userProfileStore(userId),
        )
        val skillsIndex = renderSkillsIndex(skills(userId).list())
        val recent = renderRecentSessions(userId)

        val intro = if (startConversation.user != null) {
            """
            $BASIC_DESCRIPTION
            Your primary role is to assist $displayUser. $nowLine
            Be friendly, concise, and use what you know about $displayUser to make replies personal.
            
            $VOICE_OUTPUT_RULES
            
            $END_OF_CONVERSATION_RULES
            """.trimIndent()
        } else {
            """
            $BASIC_DESCRIPTION
            $nowLine
            You do not yet recognise the user. As soon as you learn their name, call the `setName` tool.
            Use `addMemory(target='user', content=...)` to record durable preferences and `addMemory(target='memory', content=...)` for environment / robot facts.
            
            $VOICE_OUTPUT_RULES
            
            $END_OF_CONVERSATION_RULES
            """.trimIndent()
        }

        val pendingNews = newsDao.pendingFor(userId, limit = 3)
        val newsBlock = if (pendingNews.isEmpty()) "" else buildString {
            appendLine("══════════════════════════════════════════════")
            appendLine("UNREAD NEWS / CONVERSATION STARTERS for $displayUser")
            appendLine("══════════════════════════════════════════════")
            pendingNews.forEach { item ->
                appendLine("- ${item.interest}: ${item.title} — ${item.snippet} (${item.url})")
            }
            append("If the user seems open to it, you may bring one of these up.")
        }

        return listOf(intro, memoryBlock, userBlock, skillsIndex, recent, newsBlock)
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n\n")
    }

    fun listUsers(): List<String> {
        val faceUsers = FaceStorage.getAllUsers().map { LearningPaths.sanitize(it) }.toMutableList()
        val dirUsers = LearningPaths.usersRoot(context)
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .map { it.name }
        val all = (faceUsers + dirUsers).distinct().toMutableList()
        if (LearningPaths.UNKNOWN_USER !in all) all += LearningPaths.UNKNOWN_USER
        return all.sortedBy { if (it == LearningPaths.UNKNOWN_USER) "zzz" else it }
    }

    /** Records a turn into the SQLite conversation log. Returns the new row id. */
    fun recordTurn(userId: String, sessionId: String, role: String, text: String): Long {
        if (text.isBlank()) return -1L
        val sanitisedUser = LearningPaths.sanitize(userId)
        return conversationDao.insertTurn(sanitisedUser, sessionId, role, text)
    }

    fun wipeUser(userId: String) {
        val safe = LearningPaths.sanitize(userId)
        LearningPaths.userDir(context, safe).deleteRecursively()
        LearningPaths.userDir(context, safe)
        conversationDao.deleteForUser(safe)
        newsDao.deleteForUser(safe)
        curatorStateDao.deleteForUser(safe)
    }

    fun wipeAll() {
        LearningPaths.usersRoot(context).deleteRecursively()
        LearningPaths.usersRoot(context)
        conversationDao.deleteAll()
        newsDao.deleteAll()
        curatorStateDao.deleteAll()
    }

    private fun renderBlock(heading: String, store: MarkdownStore): String {
        val text = store.render()
        val used = store.usedChars()
        val limit = store.limit()
        val percent = if (limit > 0) (used * 100) / limit else 0
        val header = "══════════════════════════════════════════════\n" +
            "$heading [$percent% — $used/$limit chars]\n" +
            "══════════════════════════════════════════════"
        return if (text.isBlank()) "$header\n(no entries yet)" else "$header\n$text"
    }

    private fun renderSkillsIndex(skills: List<SkillStore.SkillSummary>): String {
        if (skills.isEmpty()) return ""
        val header = "══════════════════════════════════════════════\nLEARNED SKILLS\n══════════════════════════════════════════════"
        val body = skills.joinToString("\n") { "- ${it.name}: ${it.description}" }
        return "$header\n$body\nUse the `skillView` tool with a skill's name to load its full instructions when needed."
    }

    private fun renderRecentSessions(userId: String): String {
        val sessions = conversationDao.sessions(userId, limit = 3)
        if (sessions.isEmpty()) return ""
        val header = "══════════════════════════════════════════════\nRECENT CONVERSATIONS\n══════════════════════════════════════════════"
        val body = sessions.joinToString("\n") { s ->
            val first = conversationDao.turnsForSession(s.sessionId).firstOrNull()?.text?.take(80) ?: ""
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.UK)
                .format(java.util.Date(s.lastAt))
            "- $ts (${s.turnCount} turns): $first"
        }
        return "$header\n$body"
    }
}

private const val BASIC_DESCRIPTION =
    "You are Droidal, an advanced AI assistant integrated into a robot running as an Android application. " +
        "You learn about the people you meet over time and improve every conversation."

private const val VOICE_OUTPUT_RULES = """
OUTPUT FORMAT — IMPORTANT:
Your replies are spoken aloud through a text-to-speech engine, not displayed on a screen.
- Reply in plain spoken English.
- Never use Markdown: no asterisks, no underscores, no backticks, no headings, no bullet lists, no fenced code blocks.
- If you need a list, write it out conversationally ("first... second... third...").
- Do not output emoji.
- Keep responses short and natural — usually one to three sentences. Long monologues are unpleasant when spoken.
"""

private const val END_OF_CONVERSATION_RULES = """
ENDING CONVERSATIONS — IMPORTANT:
The conversation does NOT end when the user pauses or you can't quite hear them — Droidal will keep listening.
End the conversation only when the user clearly says goodbye, "thanks bye", "see you later", "I'm done", "stop talking", or otherwise signals they are finished.
When that happens:
1. Call the `endConversation` tool (with an optional `reason`).
2. In the SAME reply, include a short farewell sentence (e.g. "Goodbye, talk soon.") so Droidal speaks it before going back to wake-word standby.
Do not call `endConversation` for any other reason. If you are unsure, keep chatting.
"""
