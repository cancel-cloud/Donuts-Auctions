package de.lukas.donutsauctions.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PriceParserTest {

    private val parser = PriceParser()

    @Test
    fun `parses comma separated number`() {
        assertEquals(1_250L, parser.parsePrice("1,250"))
    }

    @Test
    fun `parses k suffix`() {
        assertEquals(12_000L, parser.parsePrice("12k"))
    }

    @Test
    fun `parses m suffix with decimal`() {
        assertEquals(1_500_000L, parser.parsePrice("1.5m"))
    }

    @Test
    fun `returns null for non parseable input`() {
        assertNull(parser.parsePrice("not a number"))
    }
}
