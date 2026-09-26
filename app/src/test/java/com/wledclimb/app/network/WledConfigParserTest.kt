package com.wledclimb.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WledConfigParserTest {

    // A real /json/cfg response, trimmed to the fields parsePanels() reads.
    private val rawConfig = """
        {"hw":{"led":{"total":147,"matrix":{"mpc":2,"panels":[
            {"b":true,"r":false,"v":true,"s":true,"x":0,"y":0,"h":12,"w":6},
            {"b":true,"r":false,"v":true,"s":true,"x":6,"y":0,"h":12,"w":6}
        ]}}}}
    """.trimIndent()

    @Test
    fun `parses both panels from a real controller's config`() {
        val panels = parsePanels(rawConfig)

        assertEquals(2, panels.size)
        assertEquals(
            Panel(xOffset = 0, yOffset = 0, width = 6, height = 12, bottomStart = true, rightStart = false, vertical = true, serpentine = true),
            panels[0]
        )
        assertEquals(
            Panel(xOffset = 6, yOffset = 0, width = 6, height = 12, bottomStart = true, rightStart = false, vertical = true, serpentine = true),
            panels[1]
        )
    }

    @Test
    fun `parses an empty panels array`() {
        val rawConfigWithNoPanels = """{"hw":{"led":{"matrix":{"mpc":0,"panels":[]}}}}"""

        assertEquals(emptyList<Panel>(), parsePanels(rawConfigWithNoPanels))
    }

    @Test
    fun `parses a gap array`() {
        assertEquals(listOf(1, 1, -1, 0, 1), parseGaps("[1,1,-1,0,1]"))
    }

    @Test
    fun `parses an empty gap array`() {
        assertEquals(emptyList<Int>(), parseGaps("[]"))
    }

    @Test
    fun `a 1D WLED setup is reported as a config problem, not a parse crash`() {
        // No "matrix" key at all - what a plain LED strip config looks like.
        val oneDimensional = """{"hw":{"led":{"total":30}}}"""

        val thrown = assertThrows(WledConfigException::class.java) { parsePanels(oneDimensional) }

        assertTrue(thrown.message!!.contains("2D matrix"))
    }

    @Test
    fun `a non-WLED response is reported as a config problem`() {
        val notWled = "<html><body>Router login</body></html>"

        assertThrows(WledConfigException::class.java) { parsePanels(notWled) }
    }

    @Test
    fun `a malformed gap file is reported rather than silently ignored`() {
        // Falling back to "no gaps" would render a plausible-looking grid with
        // every LED index after the first gap quietly wrong.
        assertThrows(WledConfigException::class.java) { parseGaps("""{"not":"an array"}""") }
    }
}
