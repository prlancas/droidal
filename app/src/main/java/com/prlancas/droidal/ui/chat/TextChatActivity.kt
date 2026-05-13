package com.prlancas.droidal.ui.chat

import android.os.Bundle
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prlancas.droidal.brain.llm.ChatSession
import com.prlancas.droidal.brain.llm.LlmProviderFactory
import com.prlancas.droidal.brain.tools.DroidalTools
import com.prlancas.droidal.memory.learning.LearningContext
import com.prlancas.droidal.memory.learning.LearningPaths
import com.prlancas.droidal.memory.learning.LearningStore
import com.prlancas.droidal.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Plain text chat with Droidal — used for debugging or when the user
 * can't talk (e.g. quiet room, voice not picking up). Launched from
 * Settings → Debug overlays → "Open text chat".
 *
 * Bypasses Listen / Speak entirely so there's no wake-word loop, no
 * STT, and no TTS — the user just types, taps Send, and watches the
 * model's streamed reply land in a message bubble. All other
 * conversation infrastructure (provider routing, tools,
 * [DroidalTools], MEMORY.md / USER.md system prompt) goes through
 * the same paths the voice agent uses, so this is also a clean way
 * to verify the LLM stack independently of audio I/O.
 *
 * Default Droidal UI (FaceCanvas + voice) remains untouched — opening
 * this activity does not change the launcher behaviour.
 */
class TextChatActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TextChatScreen(onClose = { finish() })
                }
            }
        }
    }
}

private data class ChatMessage(
    val role: Role,
    val text: String,
    val streaming: Boolean = false,
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextChatScreen(onClose: () -> Unit) {
    val state = rememberChatState()

    LaunchedEffect(Unit) { state.connect() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { state.dispose() } }
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text) {
        if (state.messages.isNotEmpty()) {
            state.listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            ChatTopBar(
                providerLabel = state.providerLabel,
                onClose = onClose,
                onClear = { state.clearMessages() },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                state = state.listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages) { msg -> MessageBubble(msg) }
            }
            ChatInputBar(
                value = state.input,
                onChange = { state.input = it },
                onSend = { state.send() },
                isSending = state.isSending,
                enabled = state.canSend,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    providerLabel: String,
    onClose: () -> Unit,
    onClear: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text("Droidal text chat")
                if (providerLabel.isNotBlank()) {
                    Text(
                        providerLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = {
            TextButton(onClick = onClose) { Text("\u2039 Close") }
        },
        actions = {
            TextButton(onClick = onClear) { Text("Clear") }
        },
    )
}

/**
 * Lifecycle-bound state for the text chat screen. Holds the connected
 * [ChatSession], the streaming send job, and the message buffer the
 * UI renders. Extracted to keep the composable short and to make the
 * lifecycle hooks (connect / dispose / send) visible at a glance.
 */
private class ChatState(
    val appContext: android.content.Context,
    val ioScope: CoroutineScope,
    val listState: androidx.compose.foundation.lazy.LazyListState,
    val messages: SnapshotStateList<ChatMessage>,
) {
    var input by mutableStateOf("")
    var providerLabel by mutableStateOf("")
    var isSending by mutableStateOf(false)
    private var session: ChatSession? by mutableStateOf(null)
    private var sendJob: Job? = null

    val canSend: Boolean get() = session != null

    suspend fun connect() {
        try {
            val settings = SettingsRepository.get(appContext)
            val userId = LearningPaths.sanitize(
                settings.currentUserOverride() ?: LearningPaths.UNKNOWN_USER,
            )
            LearningContext.set(userId, UUID.randomUUID().toString())
            val provider = withContext(Dispatchers.IO) {
                LlmProviderFactory.current(appContext)
            }
            providerLabel = provider.displayName
            val store = LearningStore.get(appContext)
            val systemPrompt = store.systemPromptBlock(
                com.prlancas.droidal.event.events.StartConversation(
                    startedByUser = true,
                    message = "",
                    user = userId.takeIf { it != LearningPaths.UNKNOWN_USER },
                ),
            )
            session = withContext(Dispatchers.IO) {
                provider.newSession(systemPrompt, DroidalTools())
            }
            messages += ChatMessage(
                ChatMessage.Role.SYSTEM,
                "Connected to $providerLabel. Type below to chat with Droidal.",
            )
        } catch (e: Exception) {
            messages += ChatMessage(
                ChatMessage.Role.SYSTEM,
                "Could not start a chat session: ${e.message ?: e.toString()}",
            )
        }
    }

    fun dispose() {
        sendJob?.cancel()
        runCatching { session?.close() }
        LearningContext.clear()
    }

    fun clearMessages() {
        messages.removeAll { it.role != ChatMessage.Role.SYSTEM }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || isSending) return
        val activeSession = session ?: return
        input = ""
        messages += ChatMessage(ChatMessage.Role.USER, text)
        messages += ChatMessage(ChatMessage.Role.ASSISTANT, "", streaming = true)
        val slot = messages.lastIndex
        isSending = true
        sendJob = ioScope.launch {
            try {
                val full = activeSession.send(text) { delta ->
                    val current = messages[slot]
                    messages[slot] = current.copy(text = current.text + delta)
                }
                val cleaned = full.replace("[END_CONVERSATION]", "").trim()
                messages[slot] = messages[slot].copy(
                    text = cleaned.ifBlank { messages[slot].text },
                    streaming = false,
                )
            } catch (e: Exception) {
                messages[slot] = ChatMessage(
                    ChatMessage.Role.SYSTEM,
                    "Error: ${e.message ?: e.toString()}",
                )
            } finally {
                isSending = false
            }
        }
    }
}

@Composable
private fun rememberChatState(): ChatState {
    val appContext = LocalContext.current.applicationContext
    val ioScope = remember { CoroutineScope(Dispatchers.IO) }
    val listState = rememberLazyListState()
    val messages = remember { mutableStateListOf<ChatMessage>() }
    return remember { ChatState(appContext, ioScope, listState, messages) }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val bubble = when (message.role) {
        ChatMessage.Role.USER -> BubbleStyle(
            alignment = Alignment.End,
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            label = "You",
        )
        ChatMessage.Role.ASSISTANT -> BubbleStyle(
            alignment = Alignment.Start,
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            label = "Droidal",
        )
        ChatMessage.Role.SYSTEM -> BubbleStyle(
            alignment = Alignment.CenterHorizontally,
            container = Color.Transparent,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            label = "info",
        )
    }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = when (bubble.alignment) {
            Alignment.End -> Alignment.CenterEnd
            Alignment.Start -> Alignment.CenterStart
            else -> Alignment.Center
        },
    ) {
        if (message.role == ChatMessage.Role.SYSTEM) {
            Text(
                message.text,
                color = bubble.content,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = bubble.container),
                modifier = Modifier.fillMaxWidth(BUBBLE_WIDTH_FRACTION),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        bubble.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = bubble.content,
                    )
                    Spacer(Modifier.height(2.dp))
                    val body = if (message.text.isBlank() && message.streaming) "\u2026" else message.text
                    Text(body, color = bubble.content)
                }
            }
        }
    }
}

private data class BubbleStyle(
    val alignment: Alignment.Horizontal,
    val container: Color,
    val content: Color,
    val label: String,
)

@Composable
private fun ChatInputBar(
    value: String,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(if (enabled) "Message Droidal\u2026" else "Connecting\u2026") },
            enabled = enabled && !isSending,
            singleLine = false,
            maxLines = MAX_INPUT_LINES,
        )
        Spacer(Modifier.width(8.dp))
        if (isSending) {
            OutlinedButton(onClick = {}) { Text("Sending\u2026") }
        } else {
            Button(onClick = onSend, enabled = enabled && value.isNotBlank()) { Text("Send") }
        }
    }
}

private const val BUBBLE_WIDTH_FRACTION = 0.92f
private const val MAX_INPUT_LINES = 4
