package com.prlancas.droidal.vision

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.util.Locale

/**
 * One physical thing the VLM reported seeing in a captured frame.
 *
 * This is the structured replacement for the old free-form "describe what you
 * see" string: instead of a paragraph, the vision model returns a list of these
 * so each object can be localised on the map, stored, and searched by name.
 *
 * - [canonical] is a lowercase singular common noun ("oven", "sofa", "door")
 *   the LLM normalises to at capture time; it's the key everything else joins
 *   on, so "cooker"/"stove" and "oven" collapse to one landmark.
 * - [aliases] are the synonyms the model volunteered ("cooker", "stove"), fed
 *   into the alias table so a later "go to the cooker" resolves to the oven.
 * - [bboxNorm] is `[x0, y0, x1, y1]` in 0..1 image coordinates, or null when
 *   the (often unreliable on small local models) box is missing — downstream
 *   localisation then falls back to the capture pose.
 * - [isDoor] marks passages to "more world beyond" for door-aware exploration.
 */
data class VisionObject(
    val label: String,
    val canonical: String,
    val aliases: List<String>,
    val bboxNorm: List<Float>?,
    val confidence: Float,
    val isDoor: Boolean,
) {
    /** Horizontal centre of the bounding box in 0..1, or null if no box. */
    fun bboxCenterX(): Float? = bboxNorm?.let { (it[0] + it[2]) / 2f }

    companion object {
        private const val TAG = "VisionObject"
        private const val BBOX_LEN = 4

        /**
         * Parse the model's reply into a list of objects, tolerating the usual
         * local-LLM messiness: markdown ```json fences, leading/trailing prose,
         * and missing optional fields. Returns an empty list if nothing usable
         * is found (callers treat that as "saw nothing structured").
         */
        fun parseList(raw: String?): List<VisionObject> {
            val json = extractJsonArray(raw) ?: return emptyList()
            return runCatching {
                val arr = JsonParser.parseString(json).asJsonArray
                arr.mapNotNull { el -> if (el.isJsonObject) fromJson(el.asJsonObject) else null }
            }.onFailure { Log.w(TAG, "parseList failed: ${it.message}") }
                .getOrDefault(emptyList())
        }

        /** Narrow [raw] to the outermost `[...]` so surrounding prose/fences don't break Gson. */
        private fun extractJsonArray(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val start = raw.indexOf('[')
            val end = raw.lastIndexOf(']')
            if (start < 0 || end <= start) return null
            return raw.substring(start, end + 1)
        }

        private fun fromJson(o: com.google.gson.JsonObject): VisionObject? {
            fun str(vararg keys: String): String? =
                keys.firstNotNullOfOrNull { k -> o.get(k)?.takeIf { !it.isJsonNull }?.asString?.trim() }
                    ?.takeIf { it.isNotBlank() }

            val label = str("label", "name", "object", "canonical") ?: return null
            val canonical = (str("canonical", "category", "type") ?: label)
                .lowercase(Locale.UK)
                .trim()
            val aliases = (o.get("aliases") as? JsonArray)
                ?.mapNotNull { it.takeIf { e -> !e.isJsonNull }?.asString?.trim()?.lowercase(Locale.UK) }
                ?.filter { it.isNotBlank() && it != canonical }
                ?.distinct()
                .orEmpty()
            val bbox = (o.get("bbox") as? JsonArray ?: o.get("bboxNorm") as? JsonArray)
                ?.takeIf { it.size() == BBOX_LEN }
                ?.map { runCatching { it.asFloat }.getOrDefault(0f) }
            val confidence = o.get("confidence")?.takeIf { !it.isJsonNull }
                ?.let { runCatching { it.asFloat }.getOrDefault(DEFAULT_CONFIDENCE) }
                ?: DEFAULT_CONFIDENCE
            val isDoor = o.get("door")?.takeIf { !it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrDefault(false) }
                ?: o.get("isDoor")?.takeIf { !it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrDefault(false) }
                ?: (canonical in DOOR_WORDS)
            return VisionObject(
                label = label,
                canonical = canonical,
                aliases = aliases,
                bboxNorm = bbox,
                confidence = confidence.coerceIn(0f, 1f),
                isDoor = isDoor,
            )
        }

        private const val DEFAULT_CONFIDENCE = 0.5f
        private val DOOR_WORDS = setOf("door", "doorway", "gate", "opening", "passage")

        /**
         * Prompt that asks any VLM (local LiteRT-LM, Gemini, or Ollama) for the
         * structured object list [parseList] expects. Kept here next to the
         * parser so the two stay in sync.
         */
        const val EXTRACTION_PROMPT: String =
            "You are the vision system of a home robot that is mapping a house. " +
                "List the distinct, notable, fixed physical things you can see " +
                "(furniture, appliances, fixtures, and especially doors/doorways). " +
                "Ignore people, tiny clutter, and things you are unsure about. " +
                "Respond with ONLY a JSON array and no other text. Each element is an object: " +
                "{\"canonical\": a lowercase singular common noun, " +
                "\"label\": a short human label, " +
                "\"aliases\": [other common words for the same thing], " +
                "\"bbox\": [x0,y0,x1,y1] as fractions 0..1 of the image, or null, " +
                "\"confidence\": 0..1, " +
                "\"door\": true only if it is a door, doorway, or opening to another area}. " +
                "Example: [{\"canonical\":\"oven\",\"label\":\"kitchen oven\"," +
                "\"aliases\":[\"cooker\",\"stove\"],\"bbox\":[0.1,0.4,0.3,0.9]," +
                "\"confidence\":0.8,\"door\":false}]"
    }
}
