package de.lukas.donutsauctions.parser

import de.lukas.donutsauctions.model.TrackedMenuType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DefaultCommandQueryParserTest {

    private val parser = DefaultCommandQueryParser(nowProvider = { 1234L })

    @Test
    fun `parses ah command without arguments`() {
        val context = parser.parseAhQuery("/ah")
        assertNotNull(context)
        assertEquals("", context.query)
        assertEquals("/ah", context.commandRaw)
        assertEquals(TrackedMenuType.AUCTION, context.menuType)
    }

    @Test
    fun `parses ah command with multiple words`() {
        val context = parser.parseAhQuery("/ah wooden axe")
        assertNotNull(context)
        assertEquals("wooden axe", context.query)
    }

    @Test
    fun `ignores non ah commands`() {
        assertNull(parser.parseAhQuery("/msg /ah"))
        assertNull(parser.parseAhQuery("/auction"))
    }
}
