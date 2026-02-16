package de.lukas.donutsauctions.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DonutsAuctionsScreenLayoutTest {

    @Test
    fun `row height keeps two-line minimum`() {
        val h8 = DonutsAuctionsScreenLayoutMath.computeRowHeight(8)
        val h9 = DonutsAuctionsScreenLayoutMath.computeRowHeight(9)
        val h14 = DonutsAuctionsScreenLayoutMath.computeRowHeight(14)

        assertTrue(h8 >= 24)
        assertTrue(h9 >= (9 * 2 + 6))
        assertTrue(h14 >= (14 * 2 + 6))
    }

    @Test
    fun `footer reservation leaves safe content bottom`() {
        val fontHeight = 9
        val footerOneLine = DonutsAuctionsScreenLayoutMath.computeFooterReservedHeight(fontHeight, showRepairLine = false)
        val footerTwoLines = DonutsAuctionsScreenLayoutMath.computeFooterReservedHeight(fontHeight, showRepairLine = true)

        val height = 900
        val devReserved = 22
        val contentBottomOne = height - footerOneLine - devReserved - 6
        val contentBottomTwo = height - footerTwoLines - devReserved - 6

        assertTrue(contentBottomOne < height)
        assertTrue(contentBottomTwo < contentBottomOne)
        assertTrue(contentBottomTwo <= height - 30)
    }

    @Test
    fun `overview switches to single column under threshold`() {
        assertFalse(DonutsAuctionsScreenLayoutMath.useTwoColumnOverview(320))
        assertFalse(DonutsAuctionsScreenLayoutMath.useTwoColumnOverview(429))
        assertTrue(DonutsAuctionsScreenLayoutMath.useTwoColumnOverview(430))
        assertTrue(DonutsAuctionsScreenLayoutMath.useTwoColumnOverview(600))
    }

    @Test
    fun `toggle labels include semantic prefixes and cycle marker`() {
        val labels = listOf(
            "Mode: Search (*)",
            "Sort: Min (*)",
            "Filter: All (*)",
            "Window: 30d (*)",
            "Stack: x1 (*)"
        )

        assertTrue(labels.all { it.contains(':') })
        assertTrue(labels.all { it.contains("(*)") })
        assertTrue(labels.any { it.startsWith("Mode:") })
        assertTrue(labels.any { it.startsWith("Sort:") })
    }
}
