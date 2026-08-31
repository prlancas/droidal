package com.prlancas.droidal.brain.tools

import java.util.Locale

/**
 * Classifies a physical area/room into a human-friendly room type based on
 * the set of landmark objects observed within it.
 */
object RoomClassifier {

    private val KITCHEN_OBJECTS = setOf(
        "cooker", "oven", "stove", "hob", "microwave", "fridge", "refrigerator",
        "freezer", "kettle", "toaster", "sink", "dishwasher", "cupboard",
        "pantry", "countertop", "blender", "coffee maker", "coffee machine",
    )

    private val BEDROOM_OBJECTS = setOf(
        "bed", "mattress", "wardrobe", "dresser", "nightstand", "bedside table",
        "pillow", "duvet", "closet", "alarm clock", "bunk bed",
    )

    private val LIVING_ROOM_OBJECTS = setOf(
        "sofa",
        "couch",
        "armchair",
        "television",
        "tv",
        "coffee table",
        "fireplace",
        "bookshelf",
        "bookcase",
        "rug",
        "ottoman",
        "entertainment center",
    )

    private val BATHROOM_OBJECTS = setOf(
        "toilet",
        "bath",
        "bathtub",
        "shower",
        "towel",
        "basin",
        "bathroom sink",
        "soap",
        "toothbrush",
        "mirror",
        "bidet",
    )

    private val HALL_OBJECTS = setOf(
        "coat rack",
        "shoe rack",
        "umbrella stand",
        "front door",
        "doormat",
        "hallway",
        "hall tree",
        "entry table",
    )

    private val DINING_ROOM_OBJECTS = setOf(
        "dining table",
        "dining chair",
        "sideboard",
        "china cabinet",
    )

    private val OFFICE_OBJECTS = setOf(
        "desk",
        "office chair",
        "computer",
        "monitor",
        "printer",
        "keyboard",
    )

    data class ClassificationResult(
        val name: String,
        val label: String,
        val confidence: Float,
    )

    fun classify(objects: Collection<String>): ClassificationResult {
        val canonicals = objects.map { it.trim().lowercase(Locale.UK) }
        if (canonicals.isEmpty()) {
            return ClassificationResult(name = "unknown room", label = "Unknown room", confidence = 0.0f)
        }

        val scores = mutableMapOf(
            "kitchen" to countMatches(canonicals, KITCHEN_OBJECTS),
            "bedroom" to countMatches(canonicals, BEDROOM_OBJECTS),
            "living room" to countMatches(canonicals, LIVING_ROOM_OBJECTS),
            "bathroom" to countMatches(canonicals, BATHROOM_OBJECTS),
            "hall" to countMatches(canonicals, HALL_OBJECTS),
            "dining room" to countMatches(canonicals, DINING_ROOM_OBJECTS),
            "office" to countMatches(canonicals, OFFICE_OBJECTS),
        )

        val best = scores.maxByOrNull { it.value }
        if (best != null && best.value > 0) {
            val label = best.key.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            val confidence = (best.value.toFloat() / canonicals.size.coerceAtLeast(1)).coerceIn(0.4f, 1.0f)
            return ClassificationResult(name = best.key, label = label, confidence = confidence)
        }

        return ClassificationResult(name = "room", label = "Room", confidence = 0.2f)
    }

    private fun countMatches(observed: List<String>, dictionary: Set<String>): Int =
        observed.count { item ->
            dictionary.contains(item) || dictionary.any { item.contains(it) || it.contains(item) }
        }
}
