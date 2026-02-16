package de.lukas.donutsauctions.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ItemSignatureBuilderTest {

    private val builder = ItemSignatureBuilder()

    @Test
    fun `ignores volatile auction lore lines`() {
        val a = builder.build(
            itemId = "minecraft:lever",
            displayName = "Lever",
            loreLines = listOf(
                "Price: $1.75k",
                "Seller: maxinko9911",
                "Time Left: 23h 38m 45s",
                "Worth: $4",
                "Unbreaking III"
            )
        )

        val b = builder.build(
            itemId = "minecraft:lever",
            displayName = "Lever",
            loreLines = listOf(
                "Price: $2.10k",
                "Seller: SomeoneElse",
                "Time Left: 10h 1m 2s",
                "Worth: $4",
                "Unbreaking III"
            )
        )

        assertEquals(a, b)
    }

    @Test
    fun `changes when stable variant lines differ`() {
        val a = builder.build(
            itemId = "minecraft:diamond_sword",
            displayName = "Diamond Sword",
            loreLines = listOf("Sharpness IV")
        )
        val b = builder.build(
            itemId = "minecraft:diamond_sword",
            displayName = "Diamond Sword",
            loreLines = listOf("Sharpness V")
        )

        assertNotEquals(a, b)
    }
}
