package de.lukas.donutsauctions.scanner

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AhCaptureServiceTest {

    @Test
    fun `shulker ids are excluded from capture`() {
        assertTrue(AhCaptureService.isExcludedItemId("minecraft:shulker_box"))
        assertTrue(AhCaptureService.isExcludedItemId("minecraft:white_shulker_box"))
        assertTrue(AhCaptureService.isExcludedItemId("MINECRAFT:RED_SHULKER_BOX"))
        assertFalse(AhCaptureService.isExcludedItemId("minecraft:iron_ingot"))
        assertFalse(AhCaptureService.isExcludedItemId("minecraft:chest"))
    }
}
