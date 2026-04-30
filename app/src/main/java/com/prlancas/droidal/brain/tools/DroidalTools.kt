package com.prlancas.droidal.brain.tools

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.debug.ConversationLog
import com.prlancas.droidal.debug.DebugActivityState
import com.prlancas.droidal.debug.DebugBus
import com.prlancas.droidal.face.FaceRecognitionManager
import com.prlancas.droidal.memory.learning.LearningContext
import com.prlancas.droidal.memory.learning.LearningStore
import com.prlancas.droidal.memory.learning.SkillStore
import com.prlancas.droidal.memory.learning.WebSearch
import com.prlancas.droidal.speech.Filler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Single source of truth for tools the LLM can call.
 *
 * Mirrors hermes-agent's `memory_tool` / `skill_manager_tool` /
 * `session_search_tool` / `web_search_tool` surface, scaled down to a
 * single Android client. Per-user routing is handled by [LearningContext]
 * (set by [com.prlancas.droidal.brain.Agent.haveConversation]) so each
 * tool method works without an explicit user argument — the agent stays
 * provider-agnostic and the tool schemas stay small.
 *
 * Every method returns `Map<String, Any>` — that is the shape LiteRT-LM
 * feeds back to the model as the tool's result.
 */
class DroidalTools : ToolSet {

    private val scope = CoroutineScope(Dispatchers.Default)

    private val learning: LearningStore get() = LearningStore.get(Config.getContext())

    @Tool(description = "Record the user's name. Associates the currently detected face with this name and creates a per-user learning store. Returns what Droidal already knows about them.")
    fun setName(
        @ToolParam(description = "The user's name.") name: String,
    ): Map<String, Any> {
        Log.i(TAG, "setName($name)")
        ConversationLog.append(ConversationLog.Kind.TOOL_CALL, "setName(name=$name)")
        scope.launch { FaceRecognitionManager.associateCurrentFaceWithUser(name) }
        val store = learning
        val userId = com.prlancas.droidal.memory.learning.LearningPaths.sanitize(name)
        return mapOf(
            "result" to "success",
            "name" to name,
            "memory" to store.memoryStore(userId).render(),
            "user_profile" to store.userProfileStore(userId).render(),
            "skills" to store.skills(userId).list().map { it.name },
        )
    }

    @Tool(description = "Add a new entry to Droidal's persistent memory. Pass target='user' for facts/preferences ABOUT the person you're talking to (favourite colour, allergies, schedule). Pass target='memory' for facts about the environment or robot itself (room layout, safe driving zones). Returns the entries already in that file plus the result of the add.")
    fun addMemory(
        @ToolParam(description = "Either 'memory' (Droidal's environment notes) or 'user' (about the user).") target: String,
        @ToolParam(description = "The new entry content. One sentence is best.") content: String,
    ): Map<String, Any> {
        ConversationLog.append(
            ConversationLog.Kind.TOOL_CALL,
            "addMemory(target=$target, content=${truncate(content)})",
        )
        return memoryResult(target) { it.add(content) }
    }

    @Tool(description = "Replace an existing entry in Droidal's persistent memory with a new one. Pass target='user' or target='memory'. oldText must be a unique substring of the entry you want to overwrite — read the current entries first if unsure. newContent is the new entry that replaces it.")
    fun replaceMemory(
        @ToolParam(description = "Either 'memory' or 'user'.") target: String,
        @ToolParam(description = "Unique substring of the entry to replace.") oldText: String,
        @ToolParam(description = "Replacement entry.") newContent: String,
    ): Map<String, Any> {
        ConversationLog.append(
            ConversationLog.Kind.TOOL_CALL,
            "replaceMemory(target=$target, oldText=${truncate(oldText)})",
        )
        return memoryResult(target) { it.replace(oldText, newContent) }
    }

    @Tool(description = "Delete an entry from Droidal's persistent memory. Pass target='user' or target='memory'. oldText must be a unique substring of the entry you want to remove.")
    fun removeMemory(
        @ToolParam(description = "Either 'memory' or 'user'.") target: String,
        @ToolParam(description = "Unique substring of the entry to remove.") oldText: String,
    ): Map<String, Any> {
        ConversationLog.append(
            ConversationLog.Kind.TOOL_CALL,
            "removeMemory(target=$target, oldText=${truncate(oldText)})",
        )
        return memoryResult(target) { it.remove(oldText) }
    }

    private inline fun memoryResult(
        target: String,
        op: (com.prlancas.droidal.memory.learning.MarkdownStore) -> com.prlancas.droidal.memory.learning.MarkdownStore.Result,
    ): Map<String, Any> {
        val userId = LearningContext.currentUserId
        val store = learning.storeFor(userId, target)
        val result = op(store)
        ConversationLog.append(
            ConversationLog.Kind.TOOL_RESULT,
            "memory($target) -> ${if (result.success) "success" else "error"}: ${result.message}",
        )
        return mapOf(
            "result" to if (result.success) "success" else "error",
            "message" to result.message,
            "usage" to "${result.usedChars}/${store.limit()}",
            "entries" to result.entries,
        )
    }

    @Tool(description = "List the agent-managed skills currently learned for this user. Each skill is a markdown 'how-to' that Droidal has previously written for itself. Use skillView to load one when needed.")
    fun skillsList(): Map<String, Any> {
        ConversationLog.append(ConversationLog.Kind.TOOL_CALL, "skillsList()")
        val userId = LearningContext.currentUserId
        return mapOf(
            "result" to "success",
            "skills" to learning.skills(userId).list().map {
                mapOf("name" to it.name, "slug" to it.slug, "description" to it.description)
            },
        )
    }

    @Tool(description = "Read the full SKILL.md text (or a supporting file) of a previously-learned skill.")
    fun skillView(
        @ToolParam(description = "The skill slug (kebab-case identifier).") name: String,
        @ToolParam(description = "Optional sub-path inside the skill directory (e.g. 'references/foo.md').") path: String = "",
    ): Map<String, Any> {
        ConversationLog.append(
            ConversationLog.Kind.TOOL_CALL,
            "skillView(name=$name${if (path.isNotBlank()) ", path=$path" else ""})",
        )
        val userId = LearningContext.currentUserId
        val store = learning.skills(userId)
        val text = (if (path.isBlank()) store.read(name) else store.readFile(name, path))
            ?: return mapOf("result" to "error", "error" to "Not found.")
        return mapOf("result" to "success", "name" to name, "path" to path, "content" to text)
    }

    @Tool(description = "Save a brand-new skill (procedural how-to) to Droidal's library for this user. The 'name' is a short kebab-case slug Droidal can use later to recall it; the 'content' is the full SKILL.md body in markdown.")
    fun createSkill(
        @ToolParam(description = "Skill slug (kebab-case identifier).") name: String,
        @ToolParam(description = "Full SKILL.md markdown body.") content: String,
    ): Map<String, Any> {
        ConversationLog.append(ConversationLog.Kind.TOOL_CALL, "createSkill(name=$name)")
        val userId = LearningContext.currentUserId
        val store: SkillStore = learning.skills(userId)
        return if (store.create(name, content)) mapOf("result" to "success", "name" to name)
        else mapOf("result" to "error", "error" to "Could not create skill '$name'.")
    }

    @Tool(description = "Replace the entire SKILL.md body of an existing skill. Prefer patchSkill for small edits — only use editSkill when most of the body has changed.")
    fun editSkill(
        @ToolParam(description = "Skill slug.") name: String,
        @ToolParam(description = "New full SKILL.md markdown body.") content: String,
    ): Map<String, Any> {
        ConversationLog.append(ConversationLog.Kind.TOOL_CALL, "editSkill(name=$name)")
        val userId = LearningContext.currentUserId
        val store: SkillStore = learning.skills(userId)
        return if (store.edit(name, content)) mapOf("result" to "success", "name" to name)
        else mapOf("result" to "error", "error" to "Could not edit skill '$name'.")
    }

    @Tool(description = "Make a small targeted edit to a skill's SKILL.md by replacing a unique substring. Preferred for fine-tuning a known skill rather than rewriting it.")
    fun patchSkill(
        @ToolParam(description = "Skill slug.") name: String,
        @ToolParam(description = "Unique substring already present in the skill body.") oldString: String,
        @ToolParam(description = "Replacement string.") newString: String,
    ): Map<String, Any> {
        ConversationLog.append(
            ConversationLog.Kind.TOOL_CALL,
            "patchSkill(name=$name, oldString=${truncate(oldString)})",
        )
        val userId = LearningContext.currentUserId
        val store: SkillStore = learning.skills(userId)
        val (ok, msg) = store.patch(name, oldString, newString)
        return mapOf("result" to if (ok) "success" else "error", "message" to msg, "name" to name)
    }

    @Tool(description = "Delete a previously-saved skill. Use this only when the skill turned out to be wrong or obsolete.")
    fun deleteSkill(
        @ToolParam(description = "Skill slug.") name: String,
    ): Map<String, Any> {
        ConversationLog.append(ConversationLog.Kind.TOOL_CALL, "deleteSkill(name=$name)")
        val userId = LearningContext.currentUserId
        val store: SkillStore = learning.skills(userId)
        return if (store.delete(name)) mapOf("result" to "success", "name" to name)
        else mapOf("result" to "error", "name" to name)
    }

    @Tool(description = "Search past conversations with this user using full-text search. Returns recent matches (most-recent first) so Droidal can recall things discussed previously.")
    fun searchMemory(
        @ToolParam(description = "Free-text query.") query: String,
        @ToolParam(description = "Maximum results (default 8).") max: Int = 8,
    ): Map<String, Any> {
        ConversationLog.append(
            ConversationLog.Kind.TOOL_CALL,
            "searchMemory(query=${truncate(query)}, max=$max)",
        )
        val userId = LearningContext.currentUserId
        val hits = learning.conversationDao.search(userId, query, max)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.UK)
        return mapOf(
            "result" to "success",
            "matches" to hits.map {
                mapOf(
                    "date" to fmt.format(java.util.Date(it.createdAt)),
                    "role" to it.role,
                    "snippet" to it.snippet,
                    "text" to it.text,
                )
            },
        )
    }

    @Tool(description = "Search the web for current information using DuckDuckGo. Use this when the user asks about news, recent events, or facts that may have changed since training. Returns title / url / snippet for the top results.")
    fun webSearch(
        @ToolParam(description = "Free-text web query.") query: String,
        @ToolParam(description = "Maximum results (default 5).") max: Int = 5,
    ): Map<String, Any> {
        ConversationLog.append(
            ConversationLog.Kind.TOOL_CALL,
            "webSearch(query=${truncate(query)}, max=$max)",
        )
        // Speak a "let me look that up" filler before the HTTP search so
        // the user doesn't sit in silence while DuckDuckGo responds.
        Filler.sayLookingUp()
        val results = DebugBus.withActivity(DebugActivityState.WEB_SEARCHING, detail = query) {
            runBlocking { WebSearch.search(query, max) }
        }
        return mapOf(
            "result" to "success",
            "results" to results.map {
                mapOf("title" to it.title, "url" to it.url, "snippet" to it.snippet)
            },
        )
    }

    @Tool(description = "Drive Droidal's body to an X,Y location. Coordinates are in centimetres relative to its current position. Positive X moves right, positive Y moves forward.")
    fun move(
        @ToolParam(description = "Horizontal offset in centimetres (negative = left).") x: Int,
        @ToolParam(description = "Forward offset in centimetres (negative = backward).") y: Int,
    ): Map<String, Any> {
        Log.i(TAG, "move(x=$x, y=$y)")
        ConversationLog.append(ConversationLog.Kind.TOOL_CALL, "move(x=$x, y=$y)")
        return mapOf("result" to "queued", "x" to x, "y" to y)
    }

    @Tool(description = "End the current conversation cleanly. Call this when the user has clearly said goodbye / farewell / 'thanks bye' / wants to stop talking, OR when the conversation has otherwise reached a natural close. After calling this you should still produce a short farewell sentence so Droidal speaks it; the agent loop bails out as soon as that final reply finishes.")
    fun endConversation(
        @ToolParam(description = "Optional short reason for ending (logged only, never spoken).") reason: String = "",
    ): Map<String, Any> {
        Log.i(TAG, "endConversation requested: $reason")
        ConversationLog.append(ConversationLog.Kind.TOOL_CALL, "endConversation(reason=$reason)")
        LearningContext.requestEnd()
        return mapOf("result" to "ending", "reason" to reason)
    }

    private fun truncate(s: String, max: Int = 80): String =
        if (s.length <= max) s else s.take(max - 1) + "\u2026"

    companion object {
        private const val TAG = "DroidalTools"
    }
}
