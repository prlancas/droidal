package com.prlancas.droidal.settings

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prlancas.droidal.brain.llm.GeminiTester
import com.prlancas.droidal.brain.llm.OpenRouterTester
import com.prlancas.droidal.config.Config
import com.prlancas.droidal.settings.data.Model
import com.prlancas.droidal.settings.data.ModelCatalogLoader
import com.prlancas.droidal.settings.download.DownloadRepository
import com.prlancas.droidal.settings.download.DownloadStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository.get(context) }
    val downloads = remember { DownloadRepository.get(context) }
    val models = remember { ModelCatalogLoader.loadChatModels(context) }

    var provider by rememberSaveable { mutableStateOf(settings.provider()) }
    var geminiKey by rememberSaveable { mutableStateOf(settings.geminiKey().orEmpty()) }
    var openRouterKey by rememberSaveable { mutableStateOf(settings.openRouterKey().orEmpty()) }
    var openRouterModel by rememberSaveable { mutableStateOf(settings.openRouterModel()) }
    var hfToken by rememberSaveable { mutableStateOf(settings.hfAccessToken().orEmpty()) }
    var activeLocalModel by rememberSaveable { mutableStateOf(settings.localModelName().orEmpty()) }
    var useLocalForVision by rememberSaveable { mutableStateOf(settings.useLocalForVision()) }
    var ttsSource by rememberSaveable { mutableStateOf(settings.ttsSource()) }
    var streamingMode by rememberSaveable { mutableStateOf(settings.streamingMode()) }
    var geminiStatus by remember { mutableStateOf<String?>(null) }
    var openRouterStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Droidal Settings") },
                actions = {
                    Button(onClick = {
                        // Persist everything before exiting.
                        settings.setProvider(provider)
                        settings.setGeminiKey(geminiKey.takeIf { it.isNotBlank() })
                        settings.setOpenRouterKey(openRouterKey.takeIf { it.isNotBlank() })
                        settings.setOpenRouterModel(openRouterModel.takeIf { it.isNotBlank() })
                        settings.setHfAccessToken(hfToken.takeIf { it.isNotBlank() })
                        settings.setLocalModelName(activeLocalModel.takeIf { it.isNotBlank() })
                        settings.setUseLocalForVision(useLocalForVision)
                        settings.setTtsSource(ttsSource)
                        settings.setStreamingMode(streamingMode)
                        onClose()
                    }) { Text("Done") }
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
            item { ProviderSection(provider) { provider = it } }

            item {
                GeminiSection(
                    apiKey = geminiKey,
                    onApiKeyChange = { geminiKey = it },
                    testStatus = geminiStatus,
                    onTest = {
                        geminiStatus = "Testing..."
                        scope.launch {
                            geminiStatus = GeminiTester.test(geminiKey.trim())
                        }
                    },
                )
            }

            item {
                OpenRouterSection(
                    apiKey = openRouterKey,
                    onApiKeyChange = { openRouterKey = it },
                    model = openRouterModel,
                    onModelChange = { openRouterModel = it },
                    testStatus = openRouterStatus,
                    onTest = {
                        openRouterStatus = "Testing..."
                        scope.launch {
                            openRouterStatus = OpenRouterTester.test(
                                apiKey = openRouterKey.trim(),
                                model = openRouterModel.trim(),
                            )
                        }
                    },
                )
            }

            item { HuggingFaceSection(token = hfToken, onTokenChange = { hfToken = it }) }

            item {
                Text(
                    "Local models",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            items(models, key = { it.name }) { model ->
                ModelRow(
                    model = model,
                    isActive = model.name == activeLocalModel,
                    downloads = downloads,
                    onActivate = { activeLocalModel = model.name },
                )
            }

            item {
                VisionSection(
                    useLocal = useLocalForVision,
                    onChange = { useLocalForVision = it },
                    activeModel = models.firstOrNull { it.name == activeLocalModel },
                )
            }

            item {
                TtsSection(current = ttsSource, onChange = { ttsSource = it })
            }

            item {
                StreamingSection(current = streamingMode, onChange = { streamingMode = it })
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
                "${formatGb(model.sizeInBytes)} GB · min RAM ${model.minDeviceMemoryInGb ?: '?'} GB",
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
    val context = LocalContext.current
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

private fun formatGb(bytes: Long): String =
    String.format("%.2f", bytes / 1024.0 / 1024.0 / 1024.0)

private fun formatMb(bytes: Long): String =
    String.format("%.0f", bytes / 1024.0 / 1024.0)
