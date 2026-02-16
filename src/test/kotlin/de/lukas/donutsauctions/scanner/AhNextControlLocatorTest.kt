package de.lukas.donutsauctions.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AhNextControlLocatorTest {

    @Test
    fun `accepts bottom-row arrow labeled next`() {
        val result = AhNextControlLocator.selectStrictNextCandidate(
            topSlotCount = 54,
            candidates = listOf(
                candidate(topPosition = 53, slotId = 153, slotIndex = 53, isArrow = true, displayName = "Next Page")
            )
        )

        assertEquals(153, result?.slotId)
    }

    @Test
    fun `rejects next arrows outside the bottom row`() {
        val result = AhNextControlLocator.selectStrictNextCandidate(
            topSlotCount = 54,
            candidates = listOf(
                candidate(topPosition = 8, slotId = 108, slotIndex = 8, isArrow = true, displayName = "Next")
            )
        )

        assertNull(result)
    }

    @Test
    fun `rejects bottom-row arrows with non-next label`() {
        val result = AhNextControlLocator.selectStrictNextCandidate(
            topSlotCount = 54,
            candidates = listOf(
                candidate(topPosition = 52, slotId = 152, slotIndex = 52, isArrow = true, displayName = "Confirm Purchase")
            )
        )

        assertNull(result)
    }

    @Test
    fun `selects rightmost valid next when multiple bottom-row candidates exist`() {
        val result = AhNextControlLocator.selectStrictNextCandidate(
            topSlotCount = 54,
            candidates = listOf(
                candidate(topPosition = 45, slotId = 145, slotIndex = 45, isArrow = true, displayName = "Next"),
                candidate(topPosition = 53, slotId = 153, slotIndex = 53, isArrow = true, displayName = "Next Page")
            )
        )

        assertEquals(153, result?.slotId)
    }

    private fun candidate(
        topPosition: Int,
        slotId: Int,
        slotIndex: Int,
        isArrow: Boolean,
        displayName: String
    ): AhNextControlLocator.Candidate {
        return AhNextControlLocator.Candidate(
            topPosition = topPosition,
            slotId = slotId,
            slotIndex = slotIndex,
            isArrow = isArrow,
            displayName = displayName
        )
    }
}
