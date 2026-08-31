package com.prlancas.droidal.brain.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomClassifierTest {

    @Test
    fun `classifies kitchen from typical appliances`() {
        val result = RoomClassifier.classify(listOf("oven", "cooker", "fridge", "kettle", "sink"))
        assertEquals("kitchen", result.name)
        assertEquals("Kitchen", result.label)
        assertTrue(result.confidence > 0.5f)
    }

    @Test
    fun `classifies living room from furniture and television`() {
        val result = RoomClassifier.classify(listOf("sofa", "television", "coffee table", "rug"))
        assertEquals("living room", result.name)
        assertEquals("Living Room", result.label)
    }

    @Test
    fun `classifies bedroom from bed and wardrobe`() {
        val result = RoomClassifier.classify(listOf("bed", "wardrobe", "nightstand"))
        assertEquals("bedroom", result.name)
        assertEquals("Bedroom", result.label)
    }

    @Test
    fun `classifies bathroom from toilet and bathtub`() {
        val result = RoomClassifier.classify(listOf("toilet", "bathtub", "mirror"))
        assertEquals("bathroom", result.name)
        assertEquals("Bathroom", result.label)
    }

    @Test
    fun `classifies hall from coat rack and shoe rack`() {
        val result = RoomClassifier.classify(listOf("coat rack", "shoe rack", "doormat"))
        assertEquals("hall", result.name)
        assertEquals("Hall", result.label)
    }

    @Test
    fun `handles empty list with unknown room`() {
        val result = RoomClassifier.classify(emptyList())
        assertEquals("unknown room", result.name)
    }
}
