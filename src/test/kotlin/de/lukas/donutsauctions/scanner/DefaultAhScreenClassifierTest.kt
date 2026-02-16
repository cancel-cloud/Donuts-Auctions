package de.lukas.donutsauctions.scanner

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultAhScreenClassifierTest {

    private val classifier = DefaultAhScreenClassifier()

    @Test
    fun `detects valid auction screen`() {
        val result = classifier.isAuctionScreen(
            title = "AUCTION (Page 1)",
            controlItems = setOf("SEARCH", "SORT", "NEXT")
        )
        assertTrue(result)
    }

    @Test
    fun `rejects non auction title`() {
        val result = classifier.isAuctionScreen(
            title = "CHEST",
            controlItems = setOf("SEARCH", "SORT")
        )
        assertFalse(result)
    }

    @Test
    fun `rejects missing controls`() {
        val result = classifier.isAuctionScreen(
            title = "AUCTION (Page 4)",
            controlItems = setOf("FILTER")
        )
        assertFalse(result)
    }
}
