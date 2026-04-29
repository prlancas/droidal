package com.prlancas.droidal.settings.data

import android.content.Context
import com.google.gson.annotations.SerializedName
import java.io.File

/**
 * Pared-down port of Google AI Edge Gallery's `AllowedModel` / `Model` /
 * `DefaultConfig` / `ModelAllowlist` used to drive Droidal's local-LLM picker.
 *
 * The JSON structure intentionally matches the gallery's
 * `model_allowlists/<version>.json` shape so we can ship a copy verbatim
 * (currently `1_0_12.json`) under `assets/model_allowlist.json`.
 */
data class DefaultConfig(
    @SerializedName("topK") val topK: Int? = null,
    @SerializedName("topP") val topP: Float? = null,
    @SerializedName("temperature") val temperature: Float? = null,
    @SerializedName("accelerators") val accelerators: String? = null,
    @SerializedName("visionAccelerator") val visionAccelerator: String? = null,
    @SerializedName("maxContextLength") val maxContextLength: Int? = null,
    @SerializedName("maxTokens") val maxTokens: Int? = null,
)

data class AllowedModel(
    val name: String,
    val modelId: String,
    val modelFile: String,
    val commitHash: String,
    val description: String,
    val sizeInBytes: Long,
    val defaultConfig: DefaultConfig = DefaultConfig(),
    val taskTypes: List<String> = emptyList(),
    val disabled: Boolean? = null,
    val llmSupportImage: Boolean? = null,
    val llmSupportAudio: Boolean? = null,
    val llmSupportThinking: Boolean? = null,
    val minDeviceMemoryInGb: Int? = null,
    val bestForTaskTypes: List<String>? = null,
    val url: String? = null,
) {
    fun toModel(): Model {
        val downloadUrl =
            url ?: "https://huggingface.co/$modelId/resolve/$commitHash/$modelFile?download=true"
        return Model(
            name = name,
            modelId = modelId,
            modelFile = modelFile,
            commitHash = commitHash,
            description = description,
            sizeInBytes = sizeInBytes,
            downloadUrl = downloadUrl,
            minDeviceMemoryInGb = minDeviceMemoryInGb,
            llmSupportImage = llmSupportImage == true,
            llmSupportAudio = llmSupportAudio == true,
            llmSupportThinking = llmSupportThinking == true,
            taskTypes = taskTypes,
            defaultConfig = defaultConfig,
        )
    }
}

data class ModelAllowlist(
    val models: List<AllowedModel> = emptyList(),
)

/** Resolved model record used throughout Droidal. */
data class Model(
    val name: String,
    val modelId: String,
    val modelFile: String,
    val commitHash: String,
    val description: String,
    val sizeInBytes: Long,
    val downloadUrl: String,
    val minDeviceMemoryInGb: Int?,
    val llmSupportImage: Boolean,
    val llmSupportAudio: Boolean,
    val llmSupportThinking: Boolean,
    val taskTypes: List<String>,
    val defaultConfig: DefaultConfig,
) {
    /** Path used by both the downloader and the LiteRT-LM engine, mirroring gallery's layout. */
    fun getFile(context: Context): File {
        val dir = File(
            context.getExternalFilesDir(null),
            normalizedName() + File.separator + commitHash,
        )
        return File(dir, modelFile)
    }

    fun getTmpFile(context: Context): File {
        val dir = File(
            context.getExternalFilesDir(null),
            normalizedName() + File.separator + commitHash,
        )
        return File(dir, "$modelFile.$TMP_EXT")
    }

    fun isDownloaded(context: Context): Boolean = getFile(context).exists()

    /** Same scheme gallery uses (`Model.normalizedName`): replace anything non-alphanumeric. */
    fun normalizedName(): String = name.replace(Regex("[^a-zA-Z0-9]"), "_")

    companion object {
        const val TMP_EXT = "droidaltmp"
    }
}
