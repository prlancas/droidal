package com.prlancas.droidal.settings.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson

/**
 * Loads the bundled `assets/model_allowlist.json` (a verbatim copy of Google AI Edge
 * Gallery's `model_allowlists/1_0_12.json`) and exposes the entries that make sense
 * for Droidal's chat use case.
 *
 * Gallery's special-purpose entries (`llm_tiny_garden`, `llm_mobile_actions`) are
 * filtered out because they're function-calling demos, not general chat.
 */
object ModelCatalogLoader {
    private const val TAG = "ModelCatalogLoader"
    private const val ASSET_NAME = "model_allowlist.json"

    @Volatile private var cached: List<Model>? = null

    fun loadChatModels(context: Context): List<Model> {
        cached?.let { return it }
        return synchronized(this) {
            cached?.let { return it }
            val list = try {
                val json = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
                val allowlist = Gson().fromJson(json, ModelAllowlist::class.java)
                allowlist.models
                    .filter { it.disabled != true }
                    .filter {
                        it.taskTypes.contains("llm_chat") ||
                            it.taskTypes.contains("llm_prompt_lab")
                    }
                    .map { it.toModel() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load $ASSET_NAME: ${e.message}", e)
                emptyList()
            }
            cached = list
            list
        }
    }

    fun findByName(context: Context, name: String?): Model? {
        if (name.isNullOrBlank()) return null
        return loadChatModels(context).firstOrNull { it.name == name }
    }
}
