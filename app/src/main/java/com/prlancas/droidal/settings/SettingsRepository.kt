package com.prlancas.droidal.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists Droidal's LLM provider configuration.
 *
 * Plain prefs (`droidal_settings.xml`):
 *   - provider: GEMINI | OPENROUTER | LOCAL
 *   - localModelName: matches AllowedModel.name
 *   - openRouterModel: OpenRouter model identifier (e.g. "openai/gpt-4o-mini")
 *   - useLocalForVision: when true and the active local model is multimodal,
 *                        image description routes through the local model.
 *
 * Encrypted prefs (`droidal_secrets`):
 *   - gemini_key
 *   - openrouter_key
 *   - hf_access_token (used for gated Hugging Face repos like google/gemma-3n-*)
 *
 * Wake-word gating (see [com.prlancas.droidal.listen.WakeWordMatcher]):
 *   - wake_regex: regex matched against each recognised utterance while
 *                 idle; a match wakes Droidal. Defaults to
 *                 [DEFAULT_WAKE_REGEX].
 *   - wake_always_trigger: when true, ignore the regex and treat any
 *                 recognised speech as a wake. Off by default.
 */
class SettingsRepository(context: Context) {

    enum class Provider { GEMINI, OPENROUTER, LOCAL }

    /**
     * Source for text-to-speech.
     *
     * - [ON_DEVICE]: pick a voice that doesn't need network. Keeps speech
     *   offline / private and works without connectivity.
     * - [GOOGLE_CLOUD]: prefer a network voice (typically higher quality)
     *   from Google's TTS engine.
     */
    enum class TtsSource { ON_DEVICE, GOOGLE_CLOUD }

    /**
     * Granularity at which the LLM's streamed reply is broken up into TTS
     * utterances during a conversation.
     *
     * - [SENTENCE]: only flush whole sentences (`. ! ?`). Most natural
     *   prosody; first audio plays once the model has produced a complete
     *   sentence.
     * - [CLAUSE]: also flush after commas / semicolons / colons once the
     *   pending fragment is at least [CLAUSE_MIN_CHARS] long. Snappier —
     *   the user starts hearing words sooner — at the cost of slightly
     *   choppier delivery on long sentences.
     */
    enum class StreamingMode { SENTENCE, CLAUSE }

    /**
     * How aggressively Droidal raises news / scouted items it found in the
     * background.
     *
     * - [OFF]: never speak unprompted. News still appears in Droidal's
     *   system prompt so it can mention items the next time the user wakes
     *   it.
     * - [ON_WAKE]: same as OFF — the news block is just made more prominent
     *   in the system prompt to nudge Droidal toward bringing it up early.
     * - [UNPROMPTED]: when the recognised user is in front of the camera
     *   and there's a fresh primer, Droidal initiates a conversation
     *   itself.
     * - [BOTH]: act on both wake and unprompted opportunities.
     */
    enum class ProactiveMode { OFF, ON_WAKE, UNPROMPTED, BOTH }

    private val appContext = context.applicationContext

    private val plainPrefs: SharedPreferences =
        appContext.getSharedPreferences(PLAIN_PREFS, Context.MODE_PRIVATE)

    private val encryptedPrefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            ENCRYPTED_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.e(TAG, "Falling back to plain prefs for secrets: ${e.message}", e)
        appContext.getSharedPreferences(ENCRYPTED_PREFS_FALLBACK, Context.MODE_PRIVATE)
    }

    fun provider(): Provider {
        val raw = plainPrefs.getString(KEY_PROVIDER, null) ?: return Provider.GEMINI
        return runCatching { Provider.valueOf(raw) }.getOrDefault(Provider.GEMINI)
    }

    fun setProvider(provider: Provider) {
        plainPrefs.edit().putString(KEY_PROVIDER, provider.name).apply()
    }

    fun localModelName(): String? = plainPrefs.getString(KEY_LOCAL_MODEL, null)

    fun setLocalModelName(name: String?) {
        plainPrefs.edit().putString(KEY_LOCAL_MODEL, name).apply()
    }

    fun useLocalForVision(): Boolean = plainPrefs.getBoolean(KEY_USE_LOCAL_VISION, false)

    fun setUseLocalForVision(value: Boolean) {
        plainPrefs.edit().putBoolean(KEY_USE_LOCAL_VISION, value).apply()
    }

    /**
     * Maximum number of tokens the on-device LiteRT-LM engine is asked
     * to allocate up-front (`EngineConfig.maxNumTokens`).
     *
     * This sizes the model's KV cache and attention buffers — bigger
     * means longer effective context but linearly more GPU/CPU memory
     * during init. On 8 GB phones (Samsung S23) values much above ~8K
     * for Gemma-4 / Gemma-3n start to crash the process at load time
     * with a native abort because the OpenCL allocation can't fit.
     *
     * Defaults to [DEFAULT_LOCAL_MAX_NUM_TOKENS] (5000), clamped into
     * [[MIN_LOCAL_MAX_NUM_TOKENS], [MAX_LOCAL_MAX_NUM_TOKENS]] so a
     * stale or hand-edited prefs file can't trip an OOM at startup.
     */
    fun localMaxNumTokens(): Int = clampLocalMaxNumTokens(
        plainPrefs.getInt(KEY_LOCAL_MAX_NUM_TOKENS, DEFAULT_LOCAL_MAX_NUM_TOKENS),
    )

    fun setLocalMaxNumTokens(value: Int) {
        plainPrefs.edit().putInt(KEY_LOCAL_MAX_NUM_TOKENS, clampLocalMaxNumTokens(value)).apply()
    }

    fun ttsSource(): TtsSource {
        val raw = plainPrefs.getString(KEY_TTS_SOURCE, null) ?: return TtsSource.ON_DEVICE
        return runCatching { TtsSource.valueOf(raw) }.getOrDefault(TtsSource.ON_DEVICE)
    }

    fun setTtsSource(source: TtsSource) {
        plainPrefs.edit().putString(KEY_TTS_SOURCE, source.name).apply()
    }

    fun streamingMode(): StreamingMode {
        val raw = plainPrefs.getString(KEY_STREAMING_MODE, null) ?: return StreamingMode.SENTENCE
        return runCatching { StreamingMode.valueOf(raw) }.getOrDefault(StreamingMode.SENTENCE)
    }

    fun setStreamingMode(mode: StreamingMode) {
        plainPrefs.edit().putString(KEY_STREAMING_MODE, mode.name).apply()
    }

    /** Returns the configured Gemini key, or null/blank if not set. */
    fun geminiKey(): String? = encryptedPrefs.getString(KEY_GEMINI_KEY, null)?.takeIf { it.isNotBlank() }

    fun setGeminiKey(key: String?) {
        encryptedPrefs.edit().putString(KEY_GEMINI_KEY, key?.trim()).apply()
    }

    fun hfAccessToken(): String? =
        encryptedPrefs.getString(KEY_HF_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun setHfAccessToken(token: String?) {
        encryptedPrefs.edit().putString(KEY_HF_TOKEN, token?.trim()).apply()
    }

    /** OpenRouter API key (bearer token). */
    fun openRouterKey(): String? =
        encryptedPrefs.getString(KEY_OPENROUTER_KEY, null)?.takeIf { it.isNotBlank() }

    fun setOpenRouterKey(key: String?) {
        encryptedPrefs.edit().putString(KEY_OPENROUTER_KEY, key?.trim()).apply()
    }

    /** OpenRouter model slug, e.g. "openai/gpt-4o-mini" or "anthropic/claude-3.5-sonnet". */
    fun openRouterModel(): String =
        plainPrefs.getString(KEY_OPENROUTER_MODEL, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_OPENROUTER_MODEL

    fun setOpenRouterModel(model: String?) {
        plainPrefs.edit().putString(KEY_OPENROUTER_MODEL, model?.trim()?.takeIf { it.isNotBlank() }).apply()
    }

    // -- Learning loop -------------------------------------------------------

    fun learningEnabled(): Boolean = plainPrefs.getBoolean(KEY_LEARNING_ENABLED, true)

    fun setLearningEnabled(enabled: Boolean) {
        plainPrefs.edit().putBoolean(KEY_LEARNING_ENABLED, enabled).apply()
    }

    fun proactiveMode(): ProactiveMode {
        val raw = plainPrefs.getString(KEY_PROACTIVE_MODE, null) ?: return ProactiveMode.OFF
        return runCatching { ProactiveMode.valueOf(raw) }.getOrDefault(ProactiveMode.OFF)
    }

    fun setProactiveMode(mode: ProactiveMode) {
        plainPrefs.edit().putString(KEY_PROACTIVE_MODE, mode.name).apply()
    }

    fun proactiveCooldownMinutes(): Int =
        plainPrefs.getInt(KEY_PROACTIVE_COOLDOWN, DEFAULT_PROACTIVE_COOLDOWN_MIN)

    fun setProactiveCooldownMinutes(value: Int) {
        plainPrefs.edit().putInt(KEY_PROACTIVE_COOLDOWN, value.coerceAtLeast(15)).apply()
    }

    fun reflectionIntervalHours(): Int =
        plainPrefs.getInt(KEY_REFLECTION_INTERVAL, DEFAULT_REFLECTION_INTERVAL_HOURS)

    fun setReflectionIntervalHours(value: Int) {
        plainPrefs.edit().putInt(KEY_REFLECTION_INTERVAL, value.coerceAtLeast(1)).apply()
    }

    fun newsScoutIntervalHours(): Int =
        plainPrefs.getInt(KEY_NEWS_INTERVAL, DEFAULT_NEWS_INTERVAL_HOURS)

    fun setNewsScoutIntervalHours(value: Int) {
        plainPrefs.edit().putInt(KEY_NEWS_INTERVAL, value.coerceAtLeast(1)).apply()
    }

    /**
     * Cadence at which
     * [com.prlancas.droidal.memory.learning.workers.MemoryTidyWorker]
     * sweeps each user's MEMORY.md / USER.md to drop duplicates and
     * (when over budget) ask the LLM to shorten the surviving entries.
     * Defaults to a relaxed daily cadence — tidy is cheaper-than-
     * reflection per run but still uses the local LLM when a store is
     * oversized, so we don't want it racing the reflector.
     */
    fun memoryTidyIntervalHours(): Int =
        plainPrefs.getInt(KEY_MEMORY_TIDY_INTERVAL, DEFAULT_MEMORY_TIDY_INTERVAL_HOURS)

    fun setMemoryTidyIntervalHours(value: Int) {
        plainPrefs.edit().putInt(KEY_MEMORY_TIDY_INTERVAL, value.coerceAtLeast(1)).apply()
    }

    // -- Wake word -----------------------------------------------------------

    /**
     * Regex matched against each recognised utterance while Droidal is
     * idle. A match wakes Droidal and hands the utterance to the LLM.
     * Case-insensitivity is expressed in the pattern (the default uses an
     * inline `(?i)` flag). Ignored when [wakeAlwaysTrigger] is on.
     * Defaults to [DEFAULT_WAKE_REGEX].
     */
    fun wakeRegex(): String =
        plainPrefs.getString(KEY_WAKE_REGEX, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_WAKE_REGEX

    fun setWakeRegex(pattern: String?) {
        plainPrefs.edit()
            .putString(KEY_WAKE_REGEX, pattern?.trim()?.takeIf { it.isNotBlank() })
            .apply()
    }

    /**
     * When true, skip the wake-word regex entirely: any speech the
     * recogniser picks up starts a conversation. Off by default.
     */
    fun wakeAlwaysTrigger(): Boolean =
        plainPrefs.getBoolean(KEY_WAKE_ALWAYS_TRIGGER, false)

    fun setWakeAlwaysTrigger(enabled: Boolean) {
        plainPrefs.edit().putBoolean(KEY_WAKE_ALWAYS_TRIGGER, enabled).apply()
    }

    // -- Persona -------------------------------------------------------------

    /**
     * Free-form prompt fragment that describes Droidal's persona — name,
     * tone, body, quirks, etc. Injected at the very top of the system
     * prompt block built by
     * [com.prlancas.droidal.memory.learning.LearningStore.systemPromptBlock]
     * so it shapes every reply.
     *
     * Defaults to [DEFAULT_PERSONA_PROMPT]. The settings UI lets the user
     * rewrite it ("you are an old English butler", "your name is Wall-E
     * and you live in a tin can on wheels", etc.) and reset back to the
     * default. A blank or whitespace-only stored value is treated as
     * "use default" so the prompt never collapses to nothing.
     */
    fun personaPrompt(): String =
        plainPrefs.getString(KEY_PERSONA_PROMPT, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PERSONA_PROMPT

    fun setPersonaPrompt(value: String?) {
        plainPrefs.edit()
            .putString(KEY_PERSONA_PROMPT, value?.trim()?.takeIf { it.isNotBlank() })
            .apply()
    }

    /** True iff the user has overridden [DEFAULT_PERSONA_PROMPT]. */
    fun personaIsCustomised(): Boolean =
        !plainPrefs.getString(KEY_PERSONA_PROMPT, null).isNullOrBlank()

    // -- Current user override ------------------------------------------------

    /**
     * Sticky "who is talking" override applied when nothing better is
     * known. Returned value is already sanitised via
     * [com.prlancas.droidal.memory.learning.LearningPaths.sanitize] so
     * it's safe to use as a directory key. Returns `null` when no
     * override is set — callers should fall back to the
     * `StartConversation.user` (typically a face-recognition match) and
     * finally to `LearningPaths.UNKNOWN_USER`.
     *
     * Persisted explicitly through the debug "set user" command and
     * (later) through camera-driven recognition. Kept in plain prefs —
     * it's not a secret.
     */
    fun currentUserOverride(): String? =
        plainPrefs.getString(KEY_CURRENT_USER_OVERRIDE, null)?.takeIf { it.isNotBlank() }

    fun setCurrentUserOverride(userId: String?) {
        plainPrefs.edit()
            .putString(KEY_CURRENT_USER_OVERRIDE, userId?.trim()?.takeIf { it.isNotBlank() })
            .apply()
    }

    // -- Display & background ------------------------------------------------

    /**
     * When `true`, [com.prlancas.droidal.MainActivity] overrides the
     * window's `screenBrightness` to full while the app is in the
     * foreground. Combined with `FLAG_KEEP_SCREEN_ON` this stops Android
     * (and the device's auto-brightness curve) from dimming the face.
     *
     * Defaults to `true` because Droidal is a kiosk-style face on a
     * dedicated phone — users who'd rather honour the system brightness
     * can flip this from Settings → Background & display.
     */
    fun keepScreenFullBrightness(): Boolean =
        plainPrefs.getBoolean(KEY_KEEP_SCREEN_FULL_BRIGHTNESS, true)

    fun setKeepScreenFullBrightness(enabled: Boolean) {
        plainPrefs.edit().putBoolean(KEY_KEEP_SCREEN_FULL_BRIGHTNESS, enabled).apply()
    }

    // -- Debug overlay -------------------------------------------------------

    /** Show the live partial-speech transcript over the FaceCanvas. */
    fun debugSpeechOverlayEnabled(): Boolean =
        plainPrefs.getBoolean(KEY_DEBUG_SPEECH_OVERLAY, false)

    fun setDebugSpeechOverlayEnabled(enabled: Boolean) {
        plainPrefs.edit().putBoolean(KEY_DEBUG_SPEECH_OVERLAY, enabled).apply()
    }

    /** Show the current `DebugActivityState` chip over the FaceCanvas. */
    fun debugActivityOverlayEnabled(): Boolean =
        plainPrefs.getBoolean(KEY_DEBUG_ACTIVITY_OVERLAY, false)

    fun setDebugActivityOverlayEnabled(enabled: Boolean) {
        plainPrefs.edit().putBoolean(KEY_DEBUG_ACTIVITY_OVERLAY, enabled).apply()
    }

    /**
     * Capture every user / LLM / tool transition into the in-memory
     * [com.prlancas.droidal.debug.ConversationLog] for later viewing.
     */
    fun debugConversationLogEnabled(): Boolean =
        plainPrefs.getBoolean(KEY_DEBUG_CONVERSATION_LOG, false)

    fun setDebugConversationLogEnabled(enabled: Boolean) {
        plainPrefs.edit().putBoolean(KEY_DEBUG_CONVERSATION_LOG, enabled).apply()
    }

    /** Render the on-canvas Debug button that opens the debug action menu. */
    fun debugMenuButtonEnabled(): Boolean =
        plainPrefs.getBoolean(KEY_DEBUG_MENU_BUTTON, false)

    fun setDebugMenuButtonEnabled(enabled: Boolean) {
        plainPrefs.edit().putBoolean(KEY_DEBUG_MENU_BUTTON, enabled).apply()
    }

    /**
     * Detailed logging mode. When enabled, providers / vision services
     * log the full request and response bodies they exchange with the
     * model (under the `VerboseLog` TAG), and the "what can you see"
     * path saves the captured image to the app's external files dir
     * so the developer can see exactly what was sent to the model and
     * what came back.
     *
     * Off by default — full prompt bodies can be large and contain
     * personal memory content that we don't want to spam the device
     * log with in normal use.
     */
    fun verboseLoggingEnabled(): Boolean =
        plainPrefs.getBoolean(KEY_VERBOSE_LOGGING, false)

    fun setVerboseLoggingEnabled(enabled: Boolean) {
        plainPrefs.edit().putBoolean(KEY_VERBOSE_LOGGING, enabled).apply()
    }

    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        plainPrefs.registerOnSharedPreferenceChangeListener(listener)
        encryptedPrefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        plainPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        encryptedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private const val TAG = "SettingsRepository"
        private const val PLAIN_PREFS = "droidal_settings"
        private const val ENCRYPTED_PREFS = "droidal_secrets"
        private const val ENCRYPTED_PREFS_FALLBACK = "droidal_secrets_plain"

        const val KEY_PROVIDER = "provider"
        const val KEY_LOCAL_MODEL = "local_model_name"
        const val KEY_OPENROUTER_MODEL = "openrouter_model"
        const val KEY_USE_LOCAL_VISION = "use_local_for_vision"
        const val KEY_TTS_SOURCE = "tts_source"
        const val KEY_STREAMING_MODE = "streaming_mode"
        const val KEY_GEMINI_KEY = "gemini_key"
        const val KEY_OPENROUTER_KEY = "openrouter_key"
        const val KEY_HF_TOKEN = "hf_access_token"

        const val KEY_LEARNING_ENABLED = "learning_enabled"
        const val KEY_PROACTIVE_MODE = "proactive_mode"
        const val KEY_PROACTIVE_COOLDOWN = "proactive_cooldown_minutes"
        const val KEY_REFLECTION_INTERVAL = "reflection_interval_hours"
        const val KEY_NEWS_INTERVAL = "news_scout_interval_hours"
        const val KEY_MEMORY_TIDY_INTERVAL = "memory_tidy_interval_hours"

        const val KEY_WAKE_REGEX = "wake_regex"
        const val KEY_WAKE_ALWAYS_TRIGGER = "wake_always_trigger"

        const val KEY_CURRENT_USER_OVERRIDE = "current_user_override"

        const val KEY_PERSONA_PROMPT = "persona_prompt"

        /**
         * Default persona block. Mirrors the legacy `BASIC_DESCRIPTION`
         * in `LearningStore` so users who never touch the setting see
         * the same Droidal they always have. Kept here (not in
         * `LearningStore`) so the settings UI can offer "reset to
         * default" without circular imports.
         */
        const val DEFAULT_PERSONA_PROMPT =
            "You are Droidal, an advanced AI assistant integrated into a robot running as an Android application. " +
                "You learn about the people you meet over time and improve every conversation."

        const val KEY_KEEP_SCREEN_FULL_BRIGHTNESS = "keep_screen_full_brightness"

        const val KEY_DEBUG_SPEECH_OVERLAY = "debug_speech_overlay"
        const val KEY_DEBUG_ACTIVITY_OVERLAY = "debug_activity_overlay"
        const val KEY_DEBUG_CONVERSATION_LOG = "debug_conversation_log"
        const val KEY_DEBUG_MENU_BUTTON = "debug_menu_button"
        const val KEY_VERBOSE_LOGGING = "verbose_logging"

        const val DEFAULT_PROACTIVE_COOLDOWN_MIN = 240
        const val DEFAULT_REFLECTION_INTERVAL_HOURS = 6
        const val DEFAULT_NEWS_INTERVAL_HOURS = 6
        const val DEFAULT_MEMORY_TIDY_INTERVAL_HOURS = 24

        /**
         * Default wake-word regex. Wakes on "hey" or "Droidal" and the
         * common ways the speech recogniser mis-hears the name
         * ("droid al", "droidel", …). `(?i)` = case-insensitive; `\b`
         * word boundaries stop "android" from matching "droid".
         */
        const val DEFAULT_WAKE_REGEX =
            "(?i)\\b(hey|droidal|droid al|droidel|droido|droida|droid)\\b"

        const val KEY_LOCAL_MAX_NUM_TOKENS = "local_max_num_tokens"

        /**
         * Default max-num-tokens window for the on-device LiteRT-LM
         * engine. 5000 fits comfortably inside the OpenCL allocation
         * budget on an 8 GB Samsung S23 for Gemma-4-E2B-it while still
         * giving the agent ~3-4K of headroom for system prompt + tool
         * schemas + a few turns of memory once we reserve some for
         * generation.
         */
        const val DEFAULT_LOCAL_MAX_NUM_TOKENS = 5000

        /**
         * Hard floor — going below this leaves no room for the system
         * prompt + tool schemas Droidal injects into every turn.
         */
        const val MIN_LOCAL_MAX_NUM_TOKENS = 1024

        /**
         * Hard ceiling — Gemma-4 / Gemma-3n in the bundled allowlist
         * top out at 32K, and 32K is also the upper bound that flat
         * out OOMs on the S23 GPU at load time.
         */
        const val MAX_LOCAL_MAX_NUM_TOKENS = 32000

        /**
         * Pure helper kept on the companion so unit tests can pin the
         * bounds without touching `SharedPreferences`. Returns
         * [DEFAULT_LOCAL_MAX_NUM_TOKENS] for sentinel/zero values, and
         * otherwise clamps into
         * [[MIN_LOCAL_MAX_NUM_TOKENS], [MAX_LOCAL_MAX_NUM_TOKENS]].
         */
        fun clampLocalMaxNumTokens(value: Int): Int = when {
            value <= 0 -> DEFAULT_LOCAL_MAX_NUM_TOKENS
            value < MIN_LOCAL_MAX_NUM_TOKENS -> MIN_LOCAL_MAX_NUM_TOKENS
            value > MAX_LOCAL_MAX_NUM_TOKENS -> MAX_LOCAL_MAX_NUM_TOKENS
            else -> value
        }

        // Cheap, fast, tool-calling-capable default. Users can override from
        // settings with any OpenRouter model ID.
        const val DEFAULT_OPENROUTER_MODEL = "openai/gpt-4o-mini"

        /**
         * Minimum length a clause-mode fragment must reach before we'll
         * speak it on a comma / semicolon / colon boundary. Avoids
         * stuttering on tiny clauses like "yes," at the start of a reply.
         */
        const val CLAUSE_MIN_CHARS = 25

        @Volatile private var instance: SettingsRepository? = null
        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}
