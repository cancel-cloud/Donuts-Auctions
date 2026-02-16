package de.lukas.donutsauctions.config

data class DonutsAuctionsConfig(
    val trackingEnabled: Boolean = true,
    val scanIntervalTicks: Int = 10,
    val dbPath: String = "config/donutsauctions/auctions.db",
    val maxDbSizeMb: Int = 1024,
    val debugLogging: Boolean = false,
    val minDiscountPct: Double = 10.0,
    val minSamples: Int = 5,
    val devModeEnabled: Boolean = false,
    val exportDir: String = "config/donutsauctions/exports",
    val idleBackgroundEnabled: Boolean = false,
    val idleStartDelayMs: Long = 3_000L,
    val generalScanPages: Int = 20,
    val watchScanPages: Int = 2,
    val watchItemsPerWindowCap: Int = 3,
    val pageDwellMs: Long = 500L,
    val watchCycleIntervalMs: Long = 60_000L,
    val generalCycleIntervalMs: Long = 120_000L,
    val commandMinIntervalMs: Long = 2_500L,
    val pageCaptureTimeoutMs: Long = 2_500L
) {
    fun normalized(): DonutsAuctionsConfig {
        return copy(
            scanIntervalTicks = scanIntervalTicks.coerceIn(1, 200),
            maxDbSizeMb = maxDbSizeMb.coerceIn(16, 16_384),
            dbPath = dbPath.ifBlank { "config/donutsauctions/auctions.db" },
            minDiscountPct = minDiscountPct.coerceIn(0.0, 95.0),
            minSamples = minSamples.coerceIn(1, 500),
            exportDir = exportDir.ifBlank { "config/donutsauctions/exports" },
            idleStartDelayMs = idleStartDelayMs.coerceIn(500L, 60_000L),
            generalScanPages = generalScanPages.coerceIn(1, 100),
            watchScanPages = watchScanPages.coerceIn(1, 20),
            watchItemsPerWindowCap = watchItemsPerWindowCap.coerceIn(1, 20),
            pageDwellMs = pageDwellMs.coerceIn(100L, 5_000L),
            watchCycleIntervalMs = watchCycleIntervalMs.coerceIn(5_000L, 600_000L),
            generalCycleIntervalMs = generalCycleIntervalMs.coerceIn(5_000L, 600_000L),
            commandMinIntervalMs = commandMinIntervalMs.coerceIn(500L, 60_000L),
            pageCaptureTimeoutMs = pageCaptureTimeoutMs.coerceIn(500L, 15_000L)
        )
    }
}
