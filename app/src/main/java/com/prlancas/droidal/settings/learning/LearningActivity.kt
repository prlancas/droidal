package com.prlancas.droidal.settings.learning

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.mikepenz.markdown.m3.Markdown
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import com.prlancas.droidal.event.events.StopSpeaking
import com.prlancas.droidal.memory.learning.LearningPaths
import com.prlancas.droidal.memory.learning.LearningStore
import com.prlancas.droidal.memory.learning.MarkdownStore
import com.prlancas.droidal.memory.learning.NewsItem
import com.prlancas.droidal.memory.learning.SessionSummary
import com.prlancas.droidal.memory.learning.SkillStore
import com.prlancas.droidal.memory.learning.workers.NewsScoutWorker
import com.prlancas.droidal.memory.learning.workers.ReflectorWorker
import com.prlancas.droidal.speech.MarkdownStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Per-user learning manager UI — the "Manage learning…" entry-point from
 * `SettingsActivity`.
 *
 * Renders MEMORY / USER / SKILL markdown for the picked user, allows
 * playback through the existing TTS pipeline (Say events on the EventBus
 * so the face animation lip-syncs), and provides per-entry, per-file,
 * per-user, and global clear controls.
 */
private const val TAG = "LearningActivity"

class LearningActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SafeLearningScreen(onClose = { finish() })
                }
            }
        }
    }
}

/**
 * Outer wrapper that catches any exception from [LearningScreen] and
 * renders a readable message instead of letting it propagate up and kill
 * the process. Without this, anything thrown during the first composition
 * (e.g. a SQLite migration failure, a Markdown renderer crash on
 * unexpected input) silently exits the activity.
 */
@Composable
private fun SafeLearningScreen(onClose: () -> Unit) {
    var error by remember { mutableStateOf<Throwable?>(null) }
    if (error != null) {
        ErrorScreen(error!!, onClose = onClose, onRetry = { error = null })
    } else {
        runCatching { LearningScreen(onClose = onClose, onError = { error = it }) }
            .onFailure {
                Log.e(TAG, "LearningScreen failed during composition", it)
                error = it
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ErrorScreen(error: Throwable, onClose: () -> Unit, onRetry: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Droidal Learning") },
                actions = { Button(onClick = onClose) { Text("Done") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Couldn't open the learning manager.",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                error.message ?: error::class.java.simpleName,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                error.stackTraceToString().take(2000),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry) { Text("Retry") }
                OutlinedButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LearningScreen(onClose: () -> Unit, onError: (Throwable) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableStateOf(0) }

    // The store opens the SQLite database lazily — keep it off the
    // main thread so a slow first-run schema creation doesn't ANR /
    // crash the activity launch. While it's loading we render a
    // minimal "loading…" screen.
    val storeState by produceState<LearningStore?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            runCatching { LearningStore.get(context) }
                .onFailure {
                    Log.e(TAG, "Failed to initialise LearningStore", it)
                    onError(it)
                }
                .getOrNull()
        }
    }

    if (storeState == null) {
        LoadingScaffold(onClose = onClose)
        return
    }
    val safeStore = storeState!!

    var users by remember { mutableStateOf(listOf(LearningPaths.UNKNOWN_USER)) }
    var selectedUser by rememberSaveable { mutableStateOf(LearningPaths.UNKNOWN_USER) }

    LaunchedEffect(refreshTick) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { safeStore.listUsers() }
                .onFailure { Log.w(TAG, "listUsers failed", it) }
                .getOrDefault(listOf(LearningPaths.UNKNOWN_USER))
                .ifEmpty { listOf(LearningPaths.UNKNOWN_USER) }
        }
        users = loaded
        if (selectedUser !in loaded) selectedUser = loaded.first()
    }

    var memoryText by remember { mutableStateOf("") }
    var userText by remember { mutableStateOf("") }
    var skills by remember { mutableStateOf<List<SkillStore.SkillSummary>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    var news by remember { mutableStateOf<List<NewsItem>>(emptyList()) }

    LaunchedEffect(selectedUser, refreshTick) {
        val snapshot = withContext(Dispatchers.IO) {
            runCatching {
                LearningSnapshot(
                    memory = safeStore.memoryStore(selectedUser).render(),
                    profile = safeStore.userProfileStore(selectedUser).render(),
                    skills = safeStore.skills(selectedUser).list(),
                    sessions = safeStore.conversationDao.sessions(selectedUser, limit = 25),
                    news = safeStore.newsDao.allFor(selectedUser, limit = 25),
                )
            }.onFailure { Log.w(TAG, "Loading user data failed for $selectedUser", it) }
                .getOrDefault(LearningSnapshot.EMPTY)
        }
        memoryText = snapshot.memory
        userText = snapshot.profile
        skills = snapshot.skills
        sessions = snapshot.sessions
        news = snapshot.news
    }
    @Suppress("LocalVariableName")
    val store = safeStore

    var confirmWipeUser by remember { mutableStateOf(false) }
    var confirmWipeAll by remember { mutableStateOf(false) }
    var openSkill by remember { mutableStateOf<SkillStore.SkillSummary?>(null) }
    var openSession by remember { mutableStateOf<SessionSummary?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Droidal Learning") },
                actions = {
                    OutlinedButton(onClick = { EventBus.publishAsync(StopSpeaking) }) {
                        Text("Stop voice")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onClose) { Text("Done") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { UserPickerCard(users, selectedUser) { selectedUser = it } }

            item {
                MarkdownFileCard(
                    title = "USER profile (${displayName(selectedUser)})",
                    body = userText,
                    onSpeak = { speakLong(it) },
                    onClear = {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.userProfileStore(selectedUser).clear() }
                            refreshTick++
                        }
                    },
                )
            }

            item {
                MarkdownFileCard(
                    title = "MEMORY (Droidal's notes for ${displayName(selectedUser)})",
                    body = memoryText,
                    onSpeak = { speakLong(it) },
                    onClear = {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.memoryStore(selectedUser).clear() }
                            refreshTick++
                        }
                    },
                )
            }

            item {
                Text("Skills (${skills.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (skills.isEmpty()) {
                item { Text("No skills learned yet.", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(skills, key = { it.slug }) { skill ->
                    SkillRow(
                        skill = skill,
                        onView = { openSkill = skill },
                        onSpeak = {
                            val text = store.skills(selectedUser).read(skill.slug).orEmpty()
                            speakLong(text)
                        },
                        onDelete = {
                            scope.launch {
                                withContext(Dispatchers.IO) { store.skills(selectedUser).delete(skill.slug) }
                                refreshTick++
                            }
                        },
                    )
                }
            }

            item {
                Text("Pending news (${news.count { it.presentedAt == null }} of ${news.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (news.isEmpty()) {
                item { Text("News scout has not found anything yet.", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(news, key = { it.id }) { item ->
                    NewsRow(item)
                }
            }

            item {
                Text("Conversation history (${sessions.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (sessions.isEmpty()) {
                item { Text("No saved conversations yet.", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(sessions, key = { it.sessionId }) { sess ->
                    SessionRow(
                        session = sess,
                        onOpen = { openSession = sess },
                        onDelete = {
                            scope.launch {
                                withContext(Dispatchers.IO) { store.conversationDao.deleteSession(sess.sessionId) }
                                refreshTick++
                            }
                        },
                    )
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Maintenance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                ReflectorWorker.enqueueOneShot(context, selectedUser)
                            }) { Text("Reflect now") }
                            OutlinedButton(onClick = {
                                val req = OneTimeWorkRequestBuilder<NewsScoutWorker>()
                                    .setInputData(workDataOf())
                                    .build()
                                WorkManager.getInstance(context)
                                    .enqueueUniqueWork("news-scout-once", androidx.work.ExistingWorkPolicy.REPLACE, req)
                            }) { Text("Run news scout") }
                        }
                    }
                }
            }

            item {
                DangerZoneCard(
                    onWipeUser = { confirmWipeUser = true },
                    onWipeAll = { confirmWipeAll = true },
                    selectedUser = selectedUser,
                )
            }
        }
    }

    if (confirmWipeUser) {
        AlertDialog(
            onDismissRequest = { confirmWipeUser = false },
            title = { Text("Wipe ${displayName(selectedUser)}?") },
            text = { Text("Deletes MEMORY.md, USER.md, all skills, conversations, and news for this user. This cannot be undone.") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { store.wipeUser(selectedUser) }
                        confirmWipeUser = false
                        refreshTick++
                    }
                }) { Text("Wipe") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmWipeUser = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmWipeAll) {
        AlertDialog(
            onDismissRequest = { confirmWipeAll = false },
            title = { Text("Wipe all learning?") },
            text = { Text("Deletes the learning data for every user, including all skills, conversations and news. Cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.wipeAll() }
                            confirmWipeAll = false
                            refreshTick++
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Wipe all") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmWipeAll = false }) { Text("Cancel") }
            },
        )
    }

    openSkill?.let { skill ->
        SkillSheet(
            skill = skill,
            content = remember(skill.slug, refreshTick) {
                store.skills(selectedUser).read(skill.slug).orEmpty()
            },
            onSpeak = { speakLong(it) },
            onDismiss = { openSkill = null },
        )
    }

    openSession?.let { sess ->
        SessionSheet(
            session = sess,
            store = store,
            onSpeak = { speakLong(it) },
            onDismiss = { openSession = null },
        )
    }
}

@Composable
private fun UserPickerCard(users: List<String>, current: String, onPick: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("User", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Memory and skills are scoped per user. The 'unknown' bucket holds learning from sessions where Droidal had not identified the speaker yet.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            if (users.isEmpty()) {
                Text("No users known yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    users.forEachIndexed { index, user ->
                        SegmentedButton(
                            selected = user == current,
                            onClick = { onPick(user) },
                            shape = SegmentedButtonDefaults.itemShape(index, users.size),
                            modifier = Modifier.weight(1f),
                        ) { Text(displayName(user), maxLines = 1, softWrap = false) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownFileCard(
    title: String,
    body: String,
    onSpeak: (String) -> Unit,
    onClear: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (body.isBlank()) {
                Text("(empty)", style = MaterialTheme.typography.bodySmall)
            } else {
                // Render entries (separated by §) as plain markdown sections
                // — replace the delimiter with a horizontal rule so the
                // multiplatform renderer paginates them visually.
                val rendered = body.replace(MarkdownStore.ENTRY_DELIMITER, "\n\n---\n\n")
                SafeMarkdown(content = rendered, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSpeak(body) }, enabled = body.isNotBlank()) {
                    Text("Speak")
                }
                OutlinedButton(onClick = onClear, enabled = body.isNotBlank()) {
                    Text("Clear")
                }
            }
        }
    }
}

/**
 * Holder for everything we need to repaint the per-user cards. Loaded
 * atomically from a single [Dispatchers.IO] coroutine so we never get
 * partial renders mid-load.
 */
private data class LearningSnapshot(
    val memory: String,
    val profile: String,
    val skills: List<SkillStore.SkillSummary>,
    val sessions: List<SessionSummary>,
    val news: List<NewsItem>,
) {
    companion object {
        val EMPTY = LearningSnapshot("", "", emptyList(), emptyList(), emptyList())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoadingScaffold(onClose: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Droidal Learning") },
                actions = { Button(onClick = onClose) { Text("Done") } },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Loading learning data…")
            }
        }
    }
}

/**
 * Wraps the third-party [Markdown] composable so a renderer crash falls
 * back to plain [Text] instead of killing the activity. The library is
 * generally fine, but it has historically thrown on edge-cases (very
 * long lines, dangling fences, certain unicode) and that should be a
 * cosmetic glitch, not an exit.
 */
@Composable
private fun SafeMarkdown(content: String, modifier: Modifier = Modifier) {
    var failed by remember(content) { mutableStateOf(false) }
    if (!failed) {
        runCatching { Markdown(content = content, modifier = modifier) }
            .onFailure {
                Log.w(TAG, "Markdown render failed; falling back to plain text", it)
                failed = true
            }
    }
    if (failed) {
        Text(content, modifier = modifier, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SkillRow(
    skill: SkillStore.SkillSummary,
    onView: () -> Unit,
    onSpeak: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(skill.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                AssistChip(onClick = {}, label = { Text(skill.slug) })
            }
            if (skill.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(skill.description, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onView) { Text("View") }
                OutlinedButton(onClick = onSpeak) { Text("Speak") }
                OutlinedButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun NewsRow(item: NewsItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(item.snippet, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text("${item.interest} • ${item.url}", style = MaterialTheme.typography.bodySmall)
            if (item.presentedAt != null) {
                Spacer(Modifier.height(4.dp))
                AssistChip(onClick = {}, label = { Text("Mentioned") })
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionSummary, onOpen: () -> Unit, onDelete: () -> Unit) {
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.UK) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(fmt.format(java.util.Date(session.lastAt)), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("${session.turnCount} turns", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen) { Text("Open") }
                OutlinedButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillSheet(
    skill: SkillStore.SkillSummary,
    content: String,
    onSpeak: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(skill.name) },
        text = {
            if (content.isBlank()) {
                Text("(no body)")
            } else {
                SafeMarkdown(content = content)
            }
        },
        confirmButton = { Button(onClick = { onSpeak(content) }) { Text("Speak") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionSheet(
    session: SessionSummary,
    store: LearningStore,
    onSpeak: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val turns by produceState(initialValue = emptyList<com.prlancas.droidal.memory.learning.ConversationTurn>(), session.sessionId) {
        value = withContext(Dispatchers.IO) {
            runCatching { store.conversationDao.turnsForSession(session.sessionId) }
                .onFailure { Log.w(TAG, "Loading turns for ${session.sessionId} failed", it) }
                .getOrDefault(emptyList())
        }
    }
    val transcript = remember(turns) {
        turns.joinToString("\n\n") { t ->
            val role = if (t.role == "user") "**${displayName(t.userId)}**" else "**Droidal**"
            "$role: ${t.text}"
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Session ${session.sessionId.take(6)}…") },
        text = {
            if (transcript.isBlank()) {
                Text("(empty)")
            } else {
                SafeMarkdown(content = transcript)
            }
        },
        confirmButton = {
            Button(onClick = { onSpeak(turns.filter { it.role == "assistant" }.joinToString("\n") { it.text }) }) {
                Text("Speak Droidal turns")
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun DangerZoneCard(onWipeUser: () -> Unit, onWipeAll: () -> Unit, selectedUser: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Danger zone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Removes the on-device learning data. Conversations, MEMORY/USER markdown, skills and queued news.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onWipeUser) { Text("Wipe ${displayName(selectedUser)}") }
                Button(
                    onClick = onWipeAll,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Wipe all users") }
            }
        }
    }
}

private fun displayName(slug: String): String =
    if (slug == LearningPaths.UNKNOWN_USER) "unknown" else slug.replace('-', ' ').replaceFirstChar { it.uppercase() }

/**
 * Speak a (potentially long) markdown blob via the existing TTS pipeline.
 * The shared [MarkdownStripper] removes formatting markers so TTS doesn't
 * read out punctuation as words; we then split on sentence boundaries so
 * the face animation lip-syncs naturally instead of getting one giant
 * utterance.
 */
private fun speakLong(text: String) {
    if (text.isBlank()) return
    val cleaned = MarkdownStripper.forSpeech(
        text.replace(MarkdownStore.ENTRY_DELIMITER, ". "),
    )
    if (cleaned.isBlank()) return
    cleaned
        .split(Regex("(?<=[.!?])\\s+"))
        .filter { it.isNotBlank() }
        .forEach { EventBus.publishAsync(Say(it)) }
}
