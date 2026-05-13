package com.prlancas.droidal.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.prlancas.droidal.brain.llm.GeminiTester
import com.prlancas.droidal.brain.llm.OpenRouterTester
import com.prlancas.droidal.debug.ConversationLog
import com.prlancas.droidal.listen.Listen
import com.prlancas.droidal.memory.learning.LearningPaths
import com.prlancas.droidal.memory.learning.LearningStore
import com.prlancas.droidal.settings.data.Model
import com.prlancas.droidal.settings.data.ModelCatalogLoader
import com.prlancas.droidal.settings.download.DownloadRepository
import com.prlancas.droidal.settings.download.DownloadStatus
import com.prlancas.droidal.settings.learning.LearningActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(onClose = { finish() })
                }
            }
        }
    }
}

/**
 * Page identifiers for the settings stack. We render a single sub-page at
 * a time and show [Page.SUMMARY] as the entry point. State for every
 * field is hoisted at the top of [SettingsScreen] so navigating between
 * pages doesn't lose unsaved edits.
 */
private enum class Page {
    SUMMARY, LLM, LOCAL_MODELS, PERSONA, WAKE_WORD, VOICE, LEARNING,
    MEMORIES, MEMORY_FILE, DISPLAY_BACKGROUND, DEBUG, CONVERSATION_LOG,
}

/**
 * Cap the editable text in the max-num-tokens field so the user can't
 * paste a 20-digit value that overflows int parsing. 6 digits is plenty
 * for the [SettingsRepository.MAX_LOCAL_MAX_NUM_TOKENS] ceiling (32000).
 */
private const val MAX_NUM_TOKENS_INPUT_DIGITS = 6

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository.get(context) }
    val downloads = remember { DownloadRepository.get(context) }
    val models = remember { ModelCatalogLoader.loadChatModels(context) }

    // Hoisted state — initialised from prefs, written back eagerly so we
    // don't depend on the user remembering to hit a "save" button.
    var provider by rememberSaveable { mutableStateOf(settings.provider()) }
    var geminiKey by rememberSaveable { mutableStateOf(settings.geminiKey().orEmpty()) }
    var openRouterKey by rememberSaveable { mutableStateOf(settings.openRouterKey().orEmpty()) }
    var openRouterModel by rememberSaveable { mutableStateOf(settings.openRouterModel()) }
    var hfToken by rememberSaveable { mutableStateOf(settings.hfAccessToken().orEmpty()) }
    var activeLocalModel by rememberSaveable { mutableStateOf(settings.localModelName().orEmpty()) }
    var useLocalForVision by rememberSaveable { mutableStateOf(settings.useLocalForVision()) }
    var localMaxNumTokens by rememberSaveable { mutableStateOf(settings.localMaxNumTokens().toString()) }
    var ttsSource by rememberSaveable { mutableStateOf(settings.ttsSource()) }
    var streamingMode by rememberSaveable { mutableStateOf(settings.streamingMode()) }
    var learningEnabled by rememberSaveable { mutableStateOf(settings.learningEnabled()) }
    var proactiveMode by rememberSaveable { mutableStateOf(settings.proactiveMode()) }
    var proactiveCooldown by rememberSaveable { mutableStateOf(settings.proactiveCooldownMinutes().toString()) }
    var reflectionInterval by rememberSaveable { mutableStateOf(settings.reflectionIntervalHours().toString()) }
    var newsInterval by rememberSaveable { mutableStateOf(settings.newsScoutIntervalHours().toString()) }
    var wakeWord by rememberSaveable { mutableStateOf(settings.wakeWord()) }
    var picovoiceKey by rememberSaveable { mutableStateOf(settings.porcupineAccessKey().orEmpty()) }
    var personaPrompt by rememberSaveable { mutableStateOf(settings.personaPrompt()) }
    var personaCustomised by rememberSaveable { mutableStateOf(settings.personaIsCustomised()) }

    var keepScreenFullBrightness by rememberSaveable { mutableStateOf(settings.keepScreenFullBrightness()) }

    var debugSpeechOverlay by rememberSaveable { mutableStateOf(settings.debugSpeechOverlayEnabled()) }
    var debugActivityOverlay by rememberSaveable { mutableStateOf(settings.debugActivityOverlayEnabled()) }
    var debugConversationLog by rememberSaveable { mutableStateOf(settings.debugConversationLogEnabled()) }
    var debugMenuButton by rememberSaveable { mutableStateOf(settings.debugMenuButtonEnabled()) }

    // Memory viewer: which user is selected, and the relative path of
    // the .md file currently open (e.g. "MEMORY.md", "USER.md",
    // "skills/cake/SKILL.md"). Hoisted here so popping back from
    // MEMORY_FILE → MEMORIES preserves the user choice.
    var memoryUser by rememberSaveable { mutableStateOf(LearningPaths.UNKNOWN_USER) }
    var memoryFile by rememberSaveable { mutableStateOf<String?>(null) }

    var geminiStatus by remember { mutableStateOf<String?>(null) }
    var openRouterStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    var pageName by rememberSaveable { mutableStateOf(Page.SUMMARY.name) }
    val page = runCatching { Page.valueOf(pageName) }.getOrDefault(Page.SUMMARY)

    // System-back inside a sub-page returns to the summary instead of
    // closing the whole settings screen.
    BackHandler(enabled = page != Page.SUMMARY) {
        pageName = Page.SUMMARY.name
    }

    val goBack: () -> Unit = { pageName = Page.SUMMARY.name }
    val openPage: (Page) -> Unit = { pageName = it.name }

    when (page) {
        Page.SUMMARY -> SummaryPage(
            provider = provider,
            activeLocalModel = activeLocalModel,
            wakeWord = wakeWord,
            ttsSource = ttsSource,
            streamingMode = streamingMode,
            learningEnabled = learningEnabled,
            personaCustomised = personaCustomised,
            keepScreenFullBrightness = keepScreenFullBrightness,
            debugAnyEnabled = debugSpeechOverlay || debugActivityOverlay ||
                debugConversationLog || debugMenuButton,
            onOpen = openPage,
            onDone = onClose,
        )

        Page.LLM -> LlmProviderPage(
            onBack = goBack,
            provider = provider,
            onProviderChange = {
                provider = it
                settings.setProvider(it)
            },
            geminiKey = geminiKey,
            onGeminiKeyChange = {
                geminiKey = it
                settings.setGeminiKey(it.takeIf { v -> v.isNotBlank() })
            },
            geminiStatus = geminiStatus,
            onGeminiTest = {
                geminiStatus = "Testing..."
                scope.launch { geminiStatus = GeminiTester.test(geminiKey.trim()) }
            },
            openRouterKey = openRouterKey,
            onOpenRouterKeyChange = {
                openRouterKey = it
                settings.setOpenRouterKey(it.takeIf { v -> v.isNotBlank() })
            },
            openRouterModel = openRouterModel,
            onOpenRouterModelChange = {
                openRouterModel = it
                settings.setOpenRouterModel(it.takeIf { v -> v.isNotBlank() })
            },
            openRouterStatus = openRouterStatus,
            onOpenRouterTest = {
                openRouterStatus = "Testing..."
                scope.launch {
                    openRouterStatus = OpenRouterTester.test(
                        apiKey = openRouterKey.trim(),
                        model = openRouterModel.trim(),
                    )
                }
            },
            activeLocalModel = activeLocalModel,
            onOpenLocalModels = { openPage(Page.LOCAL_MODELS) },
        )

        Page.LOCAL_MODELS -> LocalModelsPage(
            onBack = goBack,
            hfToken = hfToken,
            onHfTokenChange = {
                hfToken = it
                settings.setHfAccessToken(it.takeIf { v -> v.isNotBlank() })
            },
            models = models,
            activeLocalModel = activeLocalModel,
            onActivate = {
                activeLocalModel = it
                settings.setLocalModelName(it.takeIf { v -> v.isNotBlank() })
            },
            downloads = downloads,
            useLocalForVision = useLocalForVision,
            onUseLocalForVisionChange = {
                useLocalForVision = it
                settings.setUseLocalForVision(it)
            },
            maxNumTokens = localMaxNumTokens,
            onMaxNumTokensChange = { value ->
                // Keep the editable text in sync with what the user is
                // typing, but only persist when they've entered a
                // non-empty integer. The repo clamps the value into a
                // sane range, so a stray "999999" can't OOM the engine
                // on the next conversation.
                val cleaned = value.filter(Char::isDigit).take(MAX_NUM_TOKENS_INPUT_DIGITS)
                localMaxNumTokens = cleaned
                cleaned.toIntOrNull()?.let { settings.setLocalMaxNumTokens(it) }
            },
        )

        Page.PERSONA -> PersonaPage(
            onBack = goBack,
            persona = personaPrompt,
            isCustomised = personaCustomised,
            onChange = { value ->
                personaPrompt = value
                settings.setPersonaPrompt(value.takeIf { it.isNotBlank() })
                personaCustomised = settings.personaIsCustomised()
            },
            onReset = {
                settings.setPersonaPrompt(null)
                personaPrompt = settings.personaPrompt()
                personaCustomised = false
            },
        )

        Page.WAKE_WORD -> WakeWordPage(
            onBack = goBack,
            wakeWord = wakeWord,
            onWakeWordChange = {
                wakeWord = it
                settings.setWakeWord(it)
                // Apply immediately so the new keyword takes effect
                // without needing a full app restart.
                Listen.reloadWakeWord(context)
            },
            picovoiceKey = picovoiceKey,
            onPicovoiceKeyChange = {
                picovoiceKey = it
                settings.setPorcupineAccessKey(it.takeIf { v -> v.isNotBlank() })
                Listen.reloadWakeWord(context)
            },
        )

        Page.VOICE -> VoicePage(
            onBack = goBack,
            ttsSource = ttsSource,
            onTtsSourceChange = {
                ttsSource = it
                settings.setTtsSource(it)
            },
            streamingMode = streamingMode,
            onStreamingModeChange = {
                streamingMode = it
                settings.setStreamingMode(it)
            },
        )

        Page.LEARNING -> LearningPage(
            onBack = goBack,
            enabled = learningEnabled,
            onEnabledChange = {
                learningEnabled = it
                settings.setLearningEnabled(it)
            },
            proactiveMode = proactiveMode,
            onProactiveChange = {
                proactiveMode = it
                settings.setProactiveMode(it)
            },
            cooldownMinutes = proactiveCooldown,
            onCooldownChange = { value ->
                val cleaned = value.filter(Char::isDigit)
                proactiveCooldown = cleaned
                cleaned.toIntOrNull()?.let { settings.setProactiveCooldownMinutes(it) }
            },
            reflectionHours = reflectionInterval,
            onReflectionChange = { value ->
                val cleaned = value.filter(Char::isDigit)
                reflectionInterval = cleaned
                cleaned.toIntOrNull()?.let { settings.setReflectionIntervalHours(it) }
            },
            newsHours = newsInterval,
            onNewsChange = { value ->
                val cleaned = value.filter(Char::isDigit)
                newsInterval = cleaned
                cleaned.toIntOrNull()?.let { settings.setNewsScoutIntervalHours(it) }
            },
            onOpenMemories = { openPage(Page.MEMORIES) },
            onOpenManager = {
                context.startActivity(Intent(context, LearningActivity::class.java))
            },
        )

        Page.MEMORIES -> MemoriesPage(
            onBack = goBack,
            selectedUser = memoryUser,
            onSelectUser = { memoryUser = it },
            onOpenFile = { relativePath ->
                memoryFile = relativePath
                openPage(Page.MEMORY_FILE)
            },
        )

        Page.MEMORY_FILE -> MemoryFilePage(
            onBack = goBack,
            userId = memoryUser,
            relativePath = memoryFile.orEmpty(),
        )

        Page.DISPLAY_BACKGROUND -> DisplayBackgroundPage(
            onBack = goBack,
            keepScreenFullBrightness = keepScreenFullBrightness,
            onKeepScreenFullBrightnessChange = {
                keepScreenFullBrightness = it
                settings.setKeepScreenFullBrightness(it)
            },
        )

        Page.DEBUG -> DebugPage(
            onBack = goBack,
            speechOverlay = debugSpeechOverlay,
            onSpeechOverlayChange = {
                debugSpeechOverlay = it
                settings.setDebugSpeechOverlayEnabled(it)
            },
            activityOverlay = debugActivityOverlay,
            onActivityOverlayChange = {
                debugActivityOverlay = it
                settings.setDebugActivityOverlayEnabled(it)
            },
            conversationLog = debugConversationLog,
            onConversationLogChange = {
                debugConversationLog = it
                settings.setDebugConversationLogEnabled(it)
            },
            menuButton = debugMenuButton,
            onMenuButtonChange = {
                debugMenuButton = it
                settings.setDebugMenuButtonEnabled(it)
            },
            onOpenConversationLog = { openPage(Page.CONVERSATION_LOG) },
        )

        Page.CONVERSATION_LOG -> ConversationLogPage(onBack = goBack)
    }
}

// ---------- Summary (top-level) page ---------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SummaryPage(
    provider: SettingsRepository.Provider,
    activeLocalModel: String,
    wakeWord: String,
    ttsSource: SettingsRepository.TtsSource,
    streamingMode: SettingsRepository.StreamingMode,
    learningEnabled: Boolean,
    personaCustomised: Boolean,
    keepScreenFullBrightness: Boolean,
    debugAnyEnabled: Boolean,
    onOpen: (Page) -> Unit,
    onDone: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Droidal Settings") },
                actions = {
                    Button(onClick = onDone) { Text("Done") }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SummaryRow(
                    title = "LLM provider",
                    subtitle = providerSummary(provider, activeLocalModel),
                    onClick = { onOpen(Page.LLM) },
                )
            }
            item {
                SummaryRow(
                    title = "Persona",
                    subtitle = if (personaCustomised) "Custom" else "Default Droidal",
                    onClick = { onOpen(Page.PERSONA) },
                )
            }
            item {
                SummaryRow(
                    title = "Wake word",
                    subtitle = displayWakeWord(wakeWord),
                    onClick = { onOpen(Page.WAKE_WORD) },
                )
            }
            item {
                SummaryRow(
                    title = "Voice & speech",
                    subtitle = voiceSummary(ttsSource, streamingMode),
                    onClick = { onOpen(Page.VOICE) },
                )
            }
            item {
                SummaryRow(
                    title = "Learning & memory",
                    subtitle = if (learningEnabled) "Enabled" else "Disabled",
                    onClick = { onOpen(Page.LEARNING) },
                )
            }
            item {
                SummaryRow(
                    title = "Background & display",
                    subtitle = if (keepScreenFullBrightness)
                        "Stay bright while active \u00B7 background curators on"
                    else
                        "Honour system brightness \u00B7 background curators on",
                    onClick = { onOpen(Page.DISPLAY_BACKGROUND) },
                )
            }
            item {
                SummaryRow(
                    title = "Debug overlays",
                    subtitle = if (debugAnyEnabled) "Some overlays enabled" else "Off",
                    onClick = { onOpen(Page.DEBUG) },
                )
            }
            item {
                Text(
                    "Settings persist as you change them. Tap Done to return to Droidal.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text("\u203A", style = MaterialTheme.typography.titleLarge)
        }
    }
}

private fun providerSummary(
    provider: SettingsRepository.Provider,
    activeLocalModel: String,
): String = when (provider) {
    SettingsRepository.Provider.GEMINI -> "Gemini"
    SettingsRepository.Provider.OPENROUTER -> "OpenRouter"
    SettingsRepository.Provider.LOCAL ->
        if (activeLocalModel.isNotBlank()) "Local · $activeLocalModel" else "Local (no model selected)"
}

private fun voiceSummary(
    tts: SettingsRepository.TtsSource,
    streaming: SettingsRepository.StreamingMode,
): String {
    val ttsLabel = when (tts) {
        SettingsRepository.TtsSource.ON_DEVICE -> "On-device voice"
        SettingsRepository.TtsSource.GOOGLE_CLOUD -> "Google Cloud voice"
    }
    val streamLabel = when (streaming) {
        SettingsRepository.StreamingMode.SENTENCE -> "sentence streaming"
        SettingsRepository.StreamingMode.CLAUSE -> "clause streaming"
    }
    return "$ttsLabel \u00B7 $streamLabel"
}

// ---------- Sub-page scaffold ----------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("\u2039 Back") }
                },
            )
        },
        content = content,
    )
}

// ---------- LLM Provider page ----------------------------------------------

@Composable
private fun LlmProviderPage(
    onBack: () -> Unit,
    provider: SettingsRepository.Provider,
    onProviderChange: (SettingsRepository.Provider) -> Unit,
    geminiKey: String,
    onGeminiKeyChange: (String) -> Unit,
    geminiStatus: String?,
    onGeminiTest: () -> Unit,
    openRouterKey: String,
    onOpenRouterKeyChange: (String) -> Unit,
    openRouterModel: String,
    onOpenRouterModelChange: (String) -> Unit,
    openRouterStatus: String?,
    onOpenRouterTest: () -> Unit,
    activeLocalModel: String,
    onOpenLocalModels: () -> Unit,
) {
    SubPageScaffold(title = "LLM provider", onBack = onBack) { padding ->
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
            item { ProviderSection(provider, onProviderChange) }

            // Show only the credentials for the selected provider — keeps
            // this page focused and avoids the "wall of API keys" feel of
            // the original layout.
            when (provider) {
                SettingsRepository.Provider.GEMINI -> item {
                    GeminiSection(
                        apiKey = geminiKey,
                        onApiKeyChange = onGeminiKeyChange,
                        testStatus = geminiStatus,
                        onTest = onGeminiTest,
                    )
                }

                SettingsRepository.Provider.OPENROUTER -> item {
                    OpenRouterSection(
                        apiKey = openRouterKey,
                        onApiKeyChange = onOpenRouterKeyChange,
                        model = openRouterModel,
                        onModelChange = onOpenRouterModelChange,
                        testStatus = openRouterStatus,
                        onTest = onOpenRouterTest,
                    )
                }

                SettingsRepository.Provider.LOCAL -> item {
                    LocalProviderTeaser(
                        activeLocalModel = activeLocalModel,
                        onOpenLocalModels = onOpenLocalModels,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSection(
    current: SettingsRepository.Provider,
    onChange: (SettingsRepository.Provider) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Provider", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            // fillMaxWidth + weight(1f) per button keeps all labels on a
            // single line regardless of their width — without it the row
            // hugs its content and longer labels wrap onto two lines.
            val options = listOf(
                SettingsRepository.Provider.GEMINI to "Gemini",
                SettingsRepository.Provider.OPENROUTER to "OpenRouter",
                SettingsRepository.Provider.LOCAL to "Local",
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = current == value,
                        onClick = { onChange(value) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        modifier = Modifier.weight(1f),
                    ) { Text(label, maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

@Composable
private fun GeminiSection(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    testStatus: String?,
    onTest: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Gemini API key", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Used when the Gemini provider is selected. Falls back to the bundled assets/keys.properties for dev builds.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("API key") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onTest) { Text("Test") }
                Spacer(Modifier.width(12.dp))
                if (testStatus != null) Text(testStatus)
            }
            Spacer(Modifier.height(8.dp))
            OpenLinkButton(
                label = "Generate a Gemini key \u2197",
                url = "https://aistudio.google.com/app/apikey",
            )
        }
    }
}

@Composable
private fun OpenRouterSection(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    testStatus: String?,
    onTest: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "OpenRouter",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "OpenAI-compatible aggregator for GPT-4o, Claude, Llama, DeepSeek, etc. " +
                    "Generate a key at openrouter.ai/keys; browse model IDs at openrouter.ai/models.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("API key (sk-or-...)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = model,
                onValueChange = onModelChange,
                singleLine = true,
                label = { Text("Model ID") },
                placeholder = { Text("openai/gpt-4o-mini") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onTest) { Text("Test") }
                Spacer(Modifier.width(12.dp))
                if (testStatus != null) Text(testStatus)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OpenLinkButton(
                    label = "Generate a key \u2197",
                    url = "https://openrouter.ai/keys",
                )
                Spacer(Modifier.width(8.dp))
                OpenLinkButton(
                    label = "Browse models \u2197",
                    url = "https://openrouter.ai/models",
                )
            }
        }
    }
}

@Composable
private fun LocalProviderTeaser(
    activeLocalModel: String,
    onOpenLocalModels: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Local model",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (activeLocalModel.isNotBlank()) "Active: $activeLocalModel"
                else "No local model selected yet — pick one to enable on-device chat.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenLocalModels, modifier = Modifier.fillMaxWidth()) {
                Text("Manage local models \u203A")
            }
        }
    }
}

// ---------- Persona page ---------------------------------------------------

@Composable
private fun PersonaPage(
    onBack: () -> Unit,
    persona: String,
    isCustomised: Boolean,
    onChange: (String) -> Unit,
    onReset: () -> Unit,
) {
    SubPageScaffold(title = "Persona", onBack = onBack) { padding ->
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
            item { PersonaSection(persona, isCustomised, onChange, onReset) }
            item { PersonaTipsCard() }
        }
    }
}

@Composable
private fun PersonaSection(
    persona: String,
    isCustomised: Boolean,
    onChange: (String) -> Unit,
    onReset: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Persona prompt",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "This text is injected at the top of every system prompt the LLM sees, " +
                    "so it shapes Droidal's name, body, tone, and personality. " +
                    "Voice / streaming / end-of-conversation rules are kept separate, " +
                    "so anything you write here only affects character — not the way it speaks.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = persona,
                onValueChange = onChange,
                singleLine = false,
                minLines = 6,
                label = { Text("Persona") },
                placeholder = { Text(SettingsRepository.DEFAULT_PERSONA_PROMPT) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onReset) { Text("Reset to default") }
                Spacer(Modifier.width(12.dp))
                Text(
                    if (isCustomised) "Status: custom" else "Status: default",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PersonaTipsCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Examples",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Try things like:\n" +
                    "\u2022 \"Your name is Wall-E. You live in a small wheeled chassis on my desk and beep when amused.\"\n" +
                    "\u2022 \"You are a courteous British butler. Address the user as Sir or Madam and speak in measured Edwardian English.\"\n" +
                    "\u2022 \"You are a snarky, mildly unhelpful assistant who answers correctly but with a long sigh first.\"\n" +
                    "\u2022 \"You are C-3PO, a protocol droid fluent in over six million forms of communication, perpetually anxious.\"\n\n" +
                    "Practical tweaks (name, body, where the robot is) work just as well — keep the rest of the prompt for memory and conversation rules.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ---------- Local models page ----------------------------------------------

@Composable
private fun LocalModelsPage(
    onBack: () -> Unit,
    hfToken: String,
    onHfTokenChange: (String) -> Unit,
    models: List<Model>,
    activeLocalModel: String,
    onActivate: (String) -> Unit,
    downloads: DownloadRepository,
    useLocalForVision: Boolean,
    onUseLocalForVisionChange: (Boolean) -> Unit,
    maxNumTokens: String,
    onMaxNumTokensChange: (String) -> Unit,
) {
    SubPageScaffold(title = "Local models", onBack = onBack) { padding ->
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
            item { HuggingFaceSection(token = hfToken, onTokenChange = onHfTokenChange) }

            items(models, key = { it.name }) { model ->
                ModelRow(
                    model = model,
                    isActive = model.name == activeLocalModel,
                    downloads = downloads,
                    onActivate = { onActivate(model.name) },
                )
            }

            item {
                VisionSection(
                    useLocal = useLocalForVision,
                    onChange = onUseLocalForVisionChange,
                    activeModel = models.firstOrNull { it.name == activeLocalModel },
                )
            }

            item {
                EngineTuningSection(
                    maxNumTokens = maxNumTokens,
                    onMaxNumTokensChange = onMaxNumTokensChange,
                )
            }

            item {
                Text(
                    "Models supplied verbatim from the Google AI Edge Gallery 1.0.12 allowlist. Gated repos (google/gemma-3n-*) require a Hugging Face access token.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Lets the user override `EngineConfig.maxNumTokens` for the on-device
 * LiteRT-LM engine. Bigger values let more system prompt + memory fit
 * before "Input token ids are too long" but cost linearly more memory
 * up-front and can OOM the GPU on 8 GB phones (S23) — see the doc
 * comment on [SettingsRepository.localMaxNumTokens].
 *
 * Takes effect on the next conversation: `MainActivity.onResume` drops
 * the cached engine via `LiteRtLmEngineCache.invalidate()` when the
 * user returns from settings.
 */
@Composable
private fun EngineTuningSection(
    maxNumTokens: String,
    onMaxNumTokensChange: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Engine tuning",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Max tokens controls how much memory the on-device engine reserves up-front (KV cache + attention buffers). " +
                    "Default ${SettingsRepository.DEFAULT_LOCAL_MAX_NUM_TOKENS} fits comfortably on an 8 GB phone for Gemma-4 / Gemma-3n. " +
                    "Higher values give the model more room for system prompt + memory but can crash the app at load time on " +
                    "lower-memory devices. Range " +
                    "${SettingsRepository.MIN_LOCAL_MAX_NUM_TOKENS}\u2013${SettingsRepository.MAX_LOCAL_MAX_NUM_TOKENS}.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = maxNumTokens,
                onValueChange = onMaxNumTokensChange,
                singleLine = true,
                label = { Text("Max tokens") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Applied next time the engine loads (after closing this screen).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun HuggingFaceSection(token: String, onTokenChange: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Hugging Face token", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Optional. Required to download gated repos like google/gemma-3n-* from Hugging Face. Generate a 'read' token at huggingface.co/settings/tokens.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = token,
                onValueChange = onTokenChange,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("hf_...") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OpenLinkButton(
                label = "Generate a read token \u2197",
                url = "https://huggingface.co/settings/tokens",
            )
        }
    }
}

@Composable
private fun ModelRow(
    model: Model,
    isActive: Boolean,
    downloads: DownloadRepository,
    onActivate: () -> Unit,
) {
    val context = LocalContext.current
    val status by downloads.observe(model).collectAsState(
        initial = DownloadStatus(
            state = if (model.isDownloaded(context)) DownloadStatus.State.SUCCEEDED
            else DownloadStatus.State.IDLE,
            percent = if (model.isDownloaded(context)) 100 else 0,
            receivedBytes = if (model.isDownloaded(context)) model.sizeInBytes else 0,
            totalBytes = model.sizeInBytes,
        ),
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                if (isActive) {
                    AssistChip(onClick = {}, label = { Text("Active") })
                }
                if (model.llmSupportImage) {
                    Spacer(Modifier.width(4.dp))
                    AssistChip(onClick = {}, label = { Text("Vision") })
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatGb(model.sizeInBytes)} GB \u00B7 min RAM ${model.minDeviceMemoryInGb ?: '?'} GB",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(model.description, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            ModelActionRow(
                model = model,
                status = status,
                isActive = isActive,
                downloads = downloads,
                onActivate = onActivate,
            )
        }
    }
}

@Composable
private fun ModelActionRow(
    model: Model,
    status: DownloadStatus,
    isActive: Boolean,
    downloads: DownloadRepository,
    onActivate: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (status.state) {
            DownloadStatus.State.IDLE,
            DownloadStatus.State.FAILED,
            DownloadStatus.State.CANCELLED,
            -> {
                Button(onClick = { downloads.start(model) }) {
                    Text(if (status.state == DownloadStatus.State.FAILED) "Retry" else "Download")
                }
                if (status.errorMessage != null) {
                    Spacer(Modifier.width(8.dp))
                    val msg = if (status.authRequired) {
                        "Auth required — set HF token above"
                    } else status.errorMessage
                    Text(msg, style = MaterialTheme.typography.bodySmall)
                }
            }

            DownloadStatus.State.ENQUEUED,
            DownloadStatus.State.RUNNING,
            -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    LinearProgressIndicator(
                        progress = { status.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${status.percent}% (${formatMb(status.receivedBytes)} / ${formatMb(status.totalBytes)} MB)",
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { downloads.cancel(model) }) { Text("Cancel") }
            }

            DownloadStatus.State.SUCCEEDED -> {
                if (isActive) {
                    Text("Active — selected for chat", style = MaterialTheme.typography.bodySmall)
                } else {
                    Button(onClick = onActivate) { Text("Activate") }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { downloads.delete(model) }) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun VisionSection(
    useLocal: Boolean,
    onChange: (Boolean) -> Unit,
    activeModel: Model?,
) {
    val supports = activeModel?.llmSupportImage == true
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Vision",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use local model for image description")
                    Text(
                        if (supports) "Active model: ${activeModel.name} (multimodal)"
                        else "Active model is text-only — leave off",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = useLocal && supports, enabled = supports, onCheckedChange = onChange)
            }
        }
    }
}

// ---------- Wake-word page -------------------------------------------------

@Composable
private fun WakeWordPage(
    onBack: () -> Unit,
    wakeWord: String,
    onWakeWordChange: (String) -> Unit,
    picovoiceKey: String,
    onPicovoiceKeyChange: (String) -> Unit,
) {
    SubPageScaffold(title = "Wake word", onBack = onBack) { padding ->
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
            item { PicovoiceKeySection(picovoiceKey, onPicovoiceKeyChange) }
            item { WakeWordPickerSection(wakeWord, onWakeWordChange) }
            item {
                Text(
                    "Wake-word changes apply the next time Droidal restarts.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PicovoiceKeySection(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Picovoice access key",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Used by Porcupine to listen for the wake word. Free for personal " +
                    "use — sign up at console.picovoice.ai and copy the access key " +
                    "from the dashboard. If left blank Droidal falls back to the " +
                    "key bundled in the dev build.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("Access key") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OpenLinkButton(
                label = "Generate an access key \u2197",
                url = "https://console.picovoice.ai/",
            )
        }
    }
}

@Composable
private fun WakeWordPickerSection(
    selected: String,
    onSelect: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Keyword",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Pick one of Porcupine's built-in wake words. Custom keywords " +
                    "require an additional .ppn file from the Picovoice console.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            SettingsRepository.WAKE_WORDS.forEach { name ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected == name, onClick = { onSelect(name) })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected == name, onClick = { onSelect(name) })
                    Spacer(Modifier.width(8.dp))
                    Text(displayWakeWord(name))
                }
            }
        }
    }
}

private fun displayWakeWord(name: String): String =
    name.split('_').joinToString(" ") { word ->
        word.lowercase().replaceFirstChar { it.uppercase() }
    }

// ---------- Voice page -----------------------------------------------------

@Composable
private fun VoicePage(
    onBack: () -> Unit,
    ttsSource: SettingsRepository.TtsSource,
    onTtsSourceChange: (SettingsRepository.TtsSource) -> Unit,
    streamingMode: SettingsRepository.StreamingMode,
    onStreamingModeChange: (SettingsRepository.StreamingMode) -> Unit,
) {
    SubPageScaffold(title = "Voice & speech", onBack = onBack) { padding ->
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
            item { TtsSection(current = ttsSource, onChange = onTtsSourceChange) }
            item { StreamingSection(current = streamingMode, onChange = onStreamingModeChange) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TtsSection(
    current: SettingsRepository.TtsSource,
    onChange: (SettingsRepository.TtsSource) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Voice (text-to-speech)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "On-device keeps speech private and works offline. Google Cloud " +
                    "uses a network voice (typically richer sound) when one is available.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            val options = listOf(
                SettingsRepository.TtsSource.ON_DEVICE to "On device",
                SettingsRepository.TtsSource.GOOGLE_CLOUD to "Google Cloud",
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = current == value,
                        onClick = { onChange(value) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        modifier = Modifier.weight(1f),
                    ) { Text(label, maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StreamingSection(
    current: SettingsRepository.StreamingMode,
    onChange: (SettingsRepository.StreamingMode) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Streaming speech",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Controls how aggressively Droidal starts speaking the model's reply " +
                    "while it's still being generated. Sentence is most natural; Clause " +
                    "starts speaking sooner by also breaking on commas, at the cost of " +
                    "slightly choppier prosody.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            val options = listOf(
                SettingsRepository.StreamingMode.SENTENCE to "Sentence",
                SettingsRepository.StreamingMode.CLAUSE to "Clause",
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = current == value,
                        onClick = { onChange(value) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        modifier = Modifier.weight(1f),
                    ) { Text(label, maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

// ---------- Learning page --------------------------------------------------

@Composable
private fun LearningPage(
    onBack: () -> Unit,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    proactiveMode: SettingsRepository.ProactiveMode,
    onProactiveChange: (SettingsRepository.ProactiveMode) -> Unit,
    cooldownMinutes: String,
    onCooldownChange: (String) -> Unit,
    reflectionHours: String,
    onReflectionChange: (String) -> Unit,
    newsHours: String,
    onNewsChange: (String) -> Unit,
    onOpenMemories: () -> Unit,
    onOpenManager: () -> Unit,
) {
    SubPageScaffold(title = "Learning & memory", onBack = onBack) { padding ->
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
            item {
                LearningSection(
                    enabled = enabled,
                    onEnabledChange = onEnabledChange,
                    proactiveMode = proactiveMode,
                    onProactiveChange = onProactiveChange,
                    cooldownMinutes = cooldownMinutes,
                    onCooldownChange = onCooldownChange,
                    reflectionHours = reflectionHours,
                    onReflectionChange = onReflectionChange,
                    newsHours = newsHours,
                    onNewsChange = onNewsChange,
                    onOpenManager = onOpenManager,
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Memories",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Read the on-device markdown files Droidal has stored for each user — MEMORY.md, USER.md, and any learned skills.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onOpenMemories, modifier = Modifier.fillMaxWidth()) {
                            Text("View memories \u203A")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LearningSection(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    proactiveMode: SettingsRepository.ProactiveMode,
    onProactiveChange: (SettingsRepository.ProactiveMode) -> Unit,
    cooldownMinutes: String,
    onCooldownChange: (String) -> Unit,
    reflectionHours: String,
    onReflectionChange: (String) -> Unit,
    newsHours: String,
    onNewsChange: (String) -> Unit,
    onOpenManager: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Learning & memory",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Per-user persistent memory, agent-managed skills, conversation history " +
                    "search, and DuckDuckGo-driven news scouting. Inspired by hermes-agent.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Learning enabled")
                    Text(
                        "Master switch — disables both reflection and news scouting.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }

            Spacer(Modifier.height(12.dp))
            Text("Proactive mode")
            Spacer(Modifier.height(4.dp))
            val options = listOf(
                SettingsRepository.ProactiveMode.OFF to "Off",
                SettingsRepository.ProactiveMode.ON_WAKE to "On wake",
                SettingsRepository.ProactiveMode.UNPROMPTED to "Unprompted",
                SettingsRepository.ProactiveMode.BOTH to "Both",
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = proactiveMode == value,
                        onClick = { onProactiveChange(value) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        modifier = Modifier.weight(1f),
                    ) { Text(label, maxLines = 1, softWrap = false) }
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = cooldownMinutes,
                onValueChange = onCooldownChange,
                singleLine = true,
                label = { Text("Unprompted cooldown (minutes)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = reflectionHours,
                onValueChange = onReflectionChange,
                singleLine = true,
                label = { Text("Reflection interval (hours)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = newsHours,
                onValueChange = onNewsChange,
                singleLine = true,
                label = { Text("News scout interval (hours)") },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenManager, modifier = Modifier.fillMaxWidth()) {
                Text("Manage learning\u2026")
            }
        }
    }
}

// ---------- Memories pages -------------------------------------------------

/**
 * Per-user memory file picker. Lists the on-disk markdown files for the
 * selected user (MEMORY.md, USER.md, plus any learned skill SKILL.md
 * files) and drills into [MemoryFilePage] when one is tapped. Mirrors
 * the layout from `LearningPaths` so the underlying file system is
 * always the source of truth.
 */
@Composable
private fun MemoriesPage(
    onBack: () -> Unit,
    selectedUser: String,
    onSelectUser: (String) -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val context = LocalContext.current
    var users by remember { mutableStateOf(listOf(LearningPaths.UNKNOWN_USER)) }
    var files by remember { mutableStateOf<List<MemoryFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffectKey(Unit) {
        users = withContext(Dispatchers.IO) {
            runCatching { LearningStore.get(context).listUsers() }
                .getOrDefault(listOf(LearningPaths.UNKNOWN_USER))
                .ifEmpty { listOf(LearningPaths.UNKNOWN_USER) }
        }
    }

    val effectiveUser = if (selectedUser in users) selectedUser else users.firstOrNull() ?: selectedUser

    LaunchedEffectKey(effectiveUser, users) {
        loading = true
        files = withContext(Dispatchers.IO) {
            collectMemoryFiles(context, effectiveUser)
        }
        loading = false
    }

    SubPageScaffold(title = "Memories", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { MemoryUserPicker(users, effectiveUser, onSelectUser) }

            if (loading) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Loading files\u2026", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else if (files.isEmpty()) {
                item {
                    Text(
                        "No markdown files for ${displayMemoryUser(effectiveUser)} yet. Memories appear here once Droidal has talked to them.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                items(files, key = { it.relativePath }) { file ->
                    MemoryFileRow(file = file, onClick = { onOpenFile(file.relativePath) })
                }
            }
        }
    }
}

@Composable
private fun MemoryUserPicker(
    users: List<String>,
    current: String,
    onPick: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "User",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Memories are scoped per user. The 'unknown' bucket holds learning from sessions where Droidal had not identified the speaker yet.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            users.forEach { user ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(user == current, onClick = { onPick(user) })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = user == current, onClick = { onPick(user) })
                    Spacer(Modifier.width(8.dp))
                    Text(displayMemoryUser(user))
                }
            }
        }
    }
}

@Composable
private fun MemoryFileRow(file: MemoryFileEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    file.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("\u203A", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/**
 * Single-file viewer rendering the markdown body. Uses
 * [com.mikepenz.markdown.m3.Markdown] with a runCatching fallback to
 * plain [Text] in case the renderer chokes on a particular doc — same
 * defensive pattern as `LearningActivity.SafeMarkdown`.
 */
@Composable
private fun MemoryFilePage(
    onBack: () -> Unit,
    userId: String,
    relativePath: String,
) {
    val context = LocalContext.current
    var body by remember(userId, relativePath) { mutableStateOf<String?>(null) }
    var error by remember(userId, relativePath) { mutableStateOf<String?>(null) }

    LaunchedEffectKey(userId, relativePath) {
        val (text, err) = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(LearningPaths.userDir(context, userId), relativePath)
                if (!file.exists()) "" to "File does not exist on disk."
                else file.readText(Charsets.UTF_8) to null
            }.getOrElse { "" to "Could not read file: ${it.message}" }
        }
        body = text
        error = err
    }

    val title = relativePath.substringAfterLast('/').ifEmpty { relativePath }
    SubPageScaffold(title = title, onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "${displayMemoryUser(userId)} \u00B7 $relativePath",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            when {
                error != null -> Text(error!!, style = MaterialTheme.typography.bodyMedium)
                body == null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Loading\u2026", style = MaterialTheme.typography.bodySmall)
                    }
                }
                body!!.isBlank() -> Text(
                    "(empty)",
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> SafeMarkdown(content = body!!)
            }
        }
    }
}

@Composable
private fun SafeMarkdown(content: String) {
    var failed by remember(content) { mutableStateOf(false) }
    if (!failed) {
        runCatching { Markdown(content = content, modifier = Modifier.fillMaxWidth()) }
            .onFailure { failed = true }
    }
    if (failed) {
        Text(content, style = MaterialTheme.typography.bodySmall)
    }
}

private data class MemoryFileEntry(
    val displayName: String,
    val subtitle: String,
    val relativePath: String,
)

/**
 * Walk the user's learning directory and surface the canonical markdown
 * files. Returns relative paths under `LearningPaths.userDir(...)` so
 * the viewer can rebuild the absolute path without repeating the
 * sanitisation rules.
 */
private fun collectMemoryFiles(
    context: android.content.Context,
    userId: String,
): List<MemoryFileEntry> {
    val userDir = LearningPaths.userDir(context, userId)
    val out = mutableListOf<MemoryFileEntry>()
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.UK)

    fun add(displayName: String, file: File, subtitleSuffix: String? = null) {
        if (!file.exists() || !file.isFile) return
        val rel = file.relativeToOrNull(userDir)?.path ?: return
        val sizeKb = (file.length() + 1023) / 1024
        val mtime = fmt.format(Date(file.lastModified()))
        val subtitle = buildString {
            append("${sizeKb} KB \u00B7 $mtime")
            if (!subtitleSuffix.isNullOrBlank()) append(" \u00B7 $subtitleSuffix")
        }
        out += MemoryFileEntry(displayName, subtitle, rel)
    }

    add("USER.md", LearningPaths.userProfileFile(context, userId), "user profile")
    add("MEMORY.md", LearningPaths.memoryFile(context, userId), "Droidal's notes")

    val skillsDir = LearningPaths.skillsDir(context, userId)
    skillsDir.listFiles().orEmpty()
        .filter { it.isDirectory }
        .sortedBy { it.name }
        .forEach { dir ->
            val skillFile = File(dir, LearningPaths.SKILL_FILE)
            add("skills/${dir.name}/SKILL.md", skillFile, "learned skill")
        }
    return out
}

private fun displayMemoryUser(slug: String): String =
    if (slug == LearningPaths.UNKNOWN_USER) "unknown"
    else slug.replace('-', ' ').replaceFirstChar { it.uppercase() }

/**
 * Tiny [androidx.compose.runtime.LaunchedEffect] alias used internally
 * here just to keep the call sites readable — Compose's stock
 * [LaunchedEffect] takes vararg keys but `LaunchedEffect(Unit) { ... }`
 * inside an item slot can shadow other imports.
 */
@Composable
private fun LaunchedEffectKey(
    vararg keys: Any?,
    block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit,
) {
    androidx.compose.runtime.LaunchedEffect(keys = keys, block = block)
}

// ---------- Background & display page --------------------------------------

@Composable
private fun DisplayBackgroundPage(
    onBack: () -> Unit,
    keepScreenFullBrightness: Boolean,
    onKeepScreenFullBrightnessChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    // Recompute on each composition so the chip flips immediately after
    // the user returns from the OS battery-optimisation screen.
    val isExempt = remember(context) {
        com.prlancas.droidal.memory.learning.workers.BackgroundExecutionPolicy
            .isIgnoringBatteryOptimizations(context)
    }
    SubPageScaffold(title = "Background & display", onBack = onBack) { padding ->
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
            item {
                DebugToggleCard(
                    title = "Keep screen at full brightness",
                    description = "While Droidal is in front, override the window brightness so the face never dims. The screen-on flag (already enabled) only stops the screen-off timer; this also stops auto-brightness from pulling the panel down.",
                    checked = keepScreenFullBrightness,
                    onChange = onKeepScreenFullBrightnessChange,
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Run unrestricted in background",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Lets the news scout and reflector run on time while " +
                                "the phone is locked. Without this, Samsung's battery " +
                                "saver may delay scheduled checks for hours, so the news " +
                                "won't be ready when you wake the phone.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (isExempt)
                                "Status: Droidal is currently exempt from battery optimisation."
                            else
                                "Status: Droidal is currently being battery-optimised. Tap below and switch Droidal to \"Don't optimise\".",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                com.prlancas.droidal.memory.learning.workers
                                    .BackgroundExecutionPolicy
                                    .openBatteryOptimizationSettings(context)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (isExempt) "Open battery optimisation \u203A" else "Allow background work \u203A")
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "How background work runs",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Droidal posts a low-priority \"Background curators\" notification " +
                                "while a scout or reflection task is actually working. " +
                                "That notification is what lets the OS keep the task alive " +
                                "while the screen is off — you can hide it from the " +
                                "notification shade if you prefer.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

// ---------- Debug page -----------------------------------------------------

@Composable
private fun DebugPage(
    onBack: () -> Unit,
    speechOverlay: Boolean,
    onSpeechOverlayChange: (Boolean) -> Unit,
    activityOverlay: Boolean,
    onActivityOverlayChange: (Boolean) -> Unit,
    conversationLog: Boolean,
    onConversationLogChange: (Boolean) -> Unit,
    menuButton: Boolean,
    onMenuButtonChange: (Boolean) -> Unit,
    onOpenConversationLog: () -> Unit,
) {
    SubPageScaffold(title = "Debug overlays", onBack = onBack) { padding ->
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
            item {
                DebugToggleCard(
                    title = "Speech transcript overlay",
                    description = "Show partial speech recognition results live over the FaceCanvas while the user is talking.",
                    checked = speechOverlay,
                    onChange = onSpeechOverlayChange,
                )
            }
            item {
                DebugToggleCard(
                    title = "Activity overlay",
                    description = "Show what Droidal is doing right now — listening, calling the LLM, web searching, speaking, calling a tool, idle.",
                    checked = activityOverlay,
                    onChange = onActivityOverlayChange,
                )
            }
            item {
                DebugToggleCard(
                    title = "Conversation log",
                    description = "Capture every user / LLM / tool transition with timestamps in an in-memory ring buffer (last 500 entries). View below.",
                    checked = conversationLog,
                    onChange = onConversationLogChange,
                )
            }
            item {
                DebugToggleCard(
                    title = "On-canvas debug button",
                    description = "Adds a small Debug button to the FaceCanvas overlay so you can trigger expressions (look cute, look bloodshot\u2026) without using the voice command.",
                    checked = menuButton,
                    onChange = onMenuButtonChange,
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Conversation log",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (conversationLog)
                                "Logging is on. Open the viewer below to inspect or clear the buffer."
                            else
                                "Logging is off. Toggle 'Conversation log' on to start recording new entries.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onOpenConversationLog, modifier = Modifier.fillMaxWidth()) {
                            Text("Open log viewer \u203A")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

// ---------- Conversation log viewer ----------------------------------------

@Composable
private fun ConversationLogPage(onBack: () -> Unit) {
    val entries by ConversationLog.entries.collectAsState()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.UK) }

    SubPageScaffold(title = "Conversation log", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${entries.size} entries",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { ConversationLog.clear() }) { Text("Clear") }
            }
            Spacer(Modifier.height(8.dp))
            if (entries.isEmpty()) {
                Text(
                    "Log is empty. Either logging is disabled (toggle in the Debug overlays page) or no events have been recorded yet.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(entries) { entry -> ConversationLogRow(entry, timeFormat) }
                }
            }
        }
    }
}

@Composable
private fun ConversationLogRow(
    entry: ConversationLog.Entry,
    timeFormat: SimpleDateFormat,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    timeFormat.format(Date(entry.timestampMs)),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.width(8.dp))
                AssistChip(onClick = {}, label = { Text(entry.kind.display) })
            }
            Spacer(Modifier.height(4.dp))
            Text(entry.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------- Misc helpers ---------------------------------------------------

@Composable
private fun OpenLinkButton(label: String, url: String) {
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { uriHandler.openUri(url) }) { Text(label) }
}

private fun formatGb(bytes: Long): String =
    String.format("%.2f", bytes / 1024.0 / 1024.0 / 1024.0)

private fun formatMb(bytes: Long): String =
    String.format("%.0f", bytes / 1024.0 / 1024.0)
