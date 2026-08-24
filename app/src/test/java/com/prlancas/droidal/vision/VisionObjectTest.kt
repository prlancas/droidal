package com.prlancas.droidal.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionObjectTest {

    @Test
    fun `parses a clean json array`() {
        val raw = """
            [{"canonical":"oven","label":"kitchen oven","aliases":["cooker","stove"],
              "bbox":[0.1,0.4,0.3,0.9],"confidence":0.8,"door":false}]
        """.trimIndent()
        val objs = VisionObject.parseList(raw)
        assertEquals(1, objs.size)
        val o = objs.first()
        assertEquals("oven", o.canonical)
        assertEquals("kitchen oven", o.label)
        assertEquals(listOf("cooker", "stove"), o.aliases)
        assertEquals(0.8f, o.confidence, 1e-4f)
        assertFalse(o.isDoor)
        assertEquals(0.2f, o.bboxCenterX()!!, 1e-4f)
    }

    @Test
    fun `tolerates markdown fences and surrounding prose`() {
        val raw = "Sure! Here is what I see:\n```json\n[{\"canonical\":\"sofa\",\"label\":\"couch\"}]\n```\nHope that helps."
        val objs = VisionObject.parseList(raw)
        assertEquals(1, objs.size)
        assertEquals("sofa", objs.first().canonical)
    }

    @Test
    fun `infers door from canonical when flag missing`() {
        val objs = VisionObject.parseList("""[{"canonical":"door","label":"hallway door"}]""")
        assertTrue(objs.first().isDoor)
    }

    @Test
    fun `honours explicit door flag`() {
        val objs = VisionObject.parseList("""[{"canonical":"doorway","label":"opening","door":true}]""")
        assertTrue(objs.first().isDoor)
    }

    @Test
    fun `missing bbox yields null centre`() {
        val objs = VisionObject.parseList("""[{"canonical":"lamp","label":"floor lamp"}]""")
        assertNull(objs.first().bboxNorm)
        assertNull(objs.first().bboxCenterX())
    }

    @Test
    fun `bad bbox length is dropped`() {
        val objs = VisionObject.parseList("""[{"canonical":"table","label":"table","bbox":[0.1,0.2]}]""")
        assertNull(objs.first().bboxNorm)
    }

    @Test
    fun `empty or junk input yields empty list`() {
        assertTrue(VisionObject.parseList(null).isEmpty())
        assertTrue(VisionObject.parseList("").isEmpty())
        assertTrue(VisionObject.parseList("no json here").isEmpty())
    }

    @Test
    fun `defaults confidence and lowercases canonical`() {
        val objs = VisionObject.parseList("""[{"canonical":"Fridge","label":"Fridge"}]""")
        assertEquals("fridge", objs.first().canonical)
        assertEquals(0.5f, objs.first().confidence, 1e-4f)
    }
}
