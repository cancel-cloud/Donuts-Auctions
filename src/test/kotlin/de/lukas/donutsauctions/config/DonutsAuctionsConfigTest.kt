package de.lukas.donutsauctions.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DonutsAuctionsConfigTest {

    @Test
    fun `idle automation defaults match expected plan`() {
        val config = DonutsAuctionsConfig()
        assertFalse(config.idleBackgroundEnabled)
        assertEquals(3_000L, config.idleStartDelayMs)
        assertEquals(20, config.generalScanPages)
        assertEquals(2, config.watchScanPages)
        assertEquals(3, config.watchItemsPerWindowCap)
        assertEquals(500L, config.pageDwellMs)
        assertEquals(60_000L, config.watchCycleIntervalMs)
        assertEquals(120_000L, config.generalCycleIntervalMs)
        assertEquals(2_500L, config.commandMinIntervalMs)
        assertEquals(2_500L, config.pageCaptureTimeoutMs)
    }

    @Test
    fun `normalization clamps automation bounds`() {
        val normalized = DonutsAuctionsConfig(
            idleBackgroundEnabled = true,
            idleStartDelayMs = 100L,
            generalScanPages = 500,
            watchScanPages = 0,
            watchItemsPerWindowCap = 99,
            pageDwellMs = 10L,
            watchCycleIntervalMs = 1_000L,
            generalCycleIntervalMs = 1_000L,
            commandMinIntervalMs = 100L,
            pageCaptureTimeoutMs = 100_000L
        ).normalized()

        assertTrue(normalized.idleBackgroundEnabled)
        assertEquals(500L, normalized.idleStartDelayMs)
        assertEquals(100, normalized.generalScanPages)
        assertEquals(1, normalized.watchScanPages)
        assertEquals(20, normalized.watchItemsPerWindowCap)
        assertEquals(100L, normalized.pageDwellMs)
        assertEquals(5_000L, normalized.watchCycleIntervalMs)
        assertEquals(5_000L, normalized.generalCycleIntervalMs)
        assertEquals(500L, normalized.commandMinIntervalMs)
        assertEquals(15_000L, normalized.pageCaptureTimeoutMs)
    }
}
