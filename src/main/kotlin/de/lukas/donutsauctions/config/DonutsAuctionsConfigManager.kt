package de.lukas.donutsauctions.config

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

class DonutsAuctionsConfigManager(
    private val configPath: Path
) {

    @Volatile
    var config: DonutsAuctionsConfig = DonutsAuctionsConfig()
        private set

    @Synchronized
    fun load(): DonutsAuctionsConfig {
        if (!Files.exists(configPath)) {
            save(DonutsAuctionsConfig())
            return config
        }

        Files.newInputStream(configPath).use { input ->
            config = parse(input)
        }
        return config
    }

    @Synchronized
    fun save(newConfig: DonutsAuctionsConfig = config): DonutsAuctionsConfig {
        val normalized = newConfig.normalized()
        Files.createDirectories(configPath.parent)
        Files.newOutputStream(configPath).use { output ->
            write(output, normalized)
        }
        config = normalized
        return normalized
    }

    private fun parse(input: InputStream): DonutsAuctionsConfig {
        val properties = Properties().apply { load(input) }
        return DonutsAuctionsConfig(
            trackingEnabled = properties.getProperty("trackingEnabled")?.toBooleanStrictOrNull() ?: true,
            scanIntervalTicks = properties.getProperty("scanIntervalTicks")?.toIntOrNull() ?: 10,
            dbPath = properties.getProperty("dbPath") ?: "config/donutsauctions/auctions.db",
            maxDbSizeMb = properties.getProperty("maxDbSizeMb")?.toIntOrNull() ?: 1024,
            debugLogging = properties.getProperty("debugLogging")?.toBooleanStrictOrNull() ?: false,
            minDiscountPct = properties.getProperty("minDiscountPct")?.toDoubleOrNull() ?: 10.0,
            minSamples = properties.getProperty("minSamples")?.toIntOrNull() ?: 5,
            devModeEnabled = properties.getProperty("devModeEnabled")?.toBooleanStrictOrNull() ?: false,
            exportDir = properties.getProperty("exportDir") ?: "config/donutsauctions/exports",
            idleBackgroundEnabled = properties.getProperty("idleBackgroundEnabled")?.toBooleanStrictOrNull() ?: false,
            idleStartDelayMs = properties.getProperty("idleStartDelayMs")?.toLongOrNull() ?: 3_000L,
            generalScanPages = properties.getProperty("generalScanPages")?.toIntOrNull() ?: 20,
            watchScanPages = properties.getProperty("watchScanPages")?.toIntOrNull() ?: 2,
            watchItemsPerWindowCap = properties.getProperty("watchItemsPerWindowCap")?.toIntOrNull() ?: 3,
            pageDwellMs = properties.getProperty("pageDwellMs")?.toLongOrNull() ?: 500L,
            watchCycleIntervalMs = properties.getProperty("watchCycleIntervalMs")?.toLongOrNull() ?: 60_000L,
            generalCycleIntervalMs = properties.getProperty("generalCycleIntervalMs")?.toLongOrNull() ?: 120_000L,
            commandMinIntervalMs = properties.getProperty("commandMinIntervalMs")?.toLongOrNull() ?: 2_500L,
            pageCaptureTimeoutMs = properties.getProperty("pageCaptureTimeoutMs")?.toLongOrNull() ?: 2_500L
        ).normalized()
    }

    private fun write(output: OutputStream, config: DonutsAuctionsConfig) {
        val properties = Properties().apply {
            setProperty("trackingEnabled", config.trackingEnabled.toString())
            setProperty("scanIntervalTicks", config.scanIntervalTicks.toString())
            setProperty("dbPath", config.dbPath)
            setProperty("maxDbSizeMb", config.maxDbSizeMb.toString())
            setProperty("debugLogging", config.debugLogging.toString())
            setProperty("minDiscountPct", config.minDiscountPct.toString())
            setProperty("minSamples", config.minSamples.toString())
            setProperty("devModeEnabled", config.devModeEnabled.toString())
            setProperty("exportDir", config.exportDir)
            setProperty("idleBackgroundEnabled", config.idleBackgroundEnabled.toString())
            setProperty("idleStartDelayMs", config.idleStartDelayMs.toString())
            setProperty("generalScanPages", config.generalScanPages.toString())
            setProperty("watchScanPages", config.watchScanPages.toString())
            setProperty("watchItemsPerWindowCap", config.watchItemsPerWindowCap.toString())
            setProperty("pageDwellMs", config.pageDwellMs.toString())
            setProperty("watchCycleIntervalMs", config.watchCycleIntervalMs.toString())
            setProperty("generalCycleIntervalMs", config.generalCycleIntervalMs.toString())
            setProperty("commandMinIntervalMs", config.commandMinIntervalMs.toString())
            setProperty("pageCaptureTimeoutMs", config.pageCaptureTimeoutMs.toString())
        }
        properties.store(output, "DonutsAuctions configuration")
    }
}
