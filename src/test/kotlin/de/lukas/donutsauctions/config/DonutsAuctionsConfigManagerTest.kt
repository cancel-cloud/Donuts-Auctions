package de.lukas.donutsauctions.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DonutsAuctionsConfigManagerTest {

    @Test
    fun `persists and reloads idle automation settings`() {
        val tempFile = Files.createTempFile("donutsauctions-config-", ".properties")
        try {
            val manager = DonutsAuctionsConfigManager(tempFile)
            val expected = DonutsAuctionsConfig(
                trackingEnabled = true,
                scanIntervalTicks = 7,
                idleBackgroundEnabled = true,
                idleStartDelayMs = 4_500L,
                generalScanPages = 18,
                watchScanPages = 2,
                watchItemsPerWindowCap = 4,
                pageDwellMs = 650L,
                watchCycleIntervalMs = 70_000L,
                generalCycleIntervalMs = 130_000L,
                commandMinIntervalMs = 3_000L,
                pageCaptureTimeoutMs = 3_200L
            ).normalized()

            manager.save(expected)
            val loaded = manager.load()
            assertEquals(expected, loaded)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
}
