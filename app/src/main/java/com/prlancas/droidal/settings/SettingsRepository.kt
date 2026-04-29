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
