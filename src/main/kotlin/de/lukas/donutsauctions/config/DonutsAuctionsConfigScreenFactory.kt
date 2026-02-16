package de.lukas.donutsauctions.config

import de.lukas.donutsauctions.DonutsAuctionsClient
import me.shedaniel.clothconfig2.api.ConfigBuilder
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

object DonutsAuctionsConfigScreenFactory {
    fun create(parent: Screen?): Screen {
        val current = DonutsAuctionsClient.configManager.config
        var draft = current

        val builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Text.literal("DonutsAuctions Config"))
        val entries = builder.entryBuilder()
        val general = builder.getOrCreateCategory(Text.literal("General"))
        val automation = builder.getOrCreateCategory(Text.literal("Idle Automation"))

        general.addEntry(
            entries
                .startBooleanToggle(Text.literal("Tracking enabled"), current.trackingEnabled)
                .setDefaultValue(true)
                .setSaveConsumer { draft = draft.copy(trackingEnabled = it) }
                .build()
        )

        general.addEntry(
            entries
                .startIntField(Text.literal("Scan interval (ticks)"), current.scanIntervalTicks)
                .setDefaultValue(10)
                .setMin(1)
                .setMax(200)
                .setSaveConsumer { draft = draft.copy(scanIntervalTicks = it) }
                .build()
        )

        general.addEntry(
            entries
                .startStrField(Text.literal("SQLite DB path"), current.dbPath)
                .setDefaultValue("config/donutsauctions/auctions.db")
                .setSaveConsumer { draft = draft.copy(dbPath = it) }
                .build()
        )

        general.addEntry(
            entries
                .startIntField(Text.literal("Max DB size (MB)"), current.maxDbSizeMb)
                .setDefaultValue(1024)
                .setMin(16)
                .setMax(16_384)
                .setSaveConsumer { draft = draft.copy(maxDbSizeMb = it) }
                .build()
        )

        general.addEntry(
            entries
                .startBooleanToggle(Text.literal("Debug logging"), current.debugLogging)
                .setDefaultValue(false)
                .setSaveConsumer { draft = draft.copy(debugLogging = it) }
                .build()
        )

        general.addEntry(
            entries
                .startDoubleField(Text.literal("Min discount (%)"), current.minDiscountPct)
                .setDefaultValue(10.0)
                .setMin(0.0)
                .setMax(95.0)
                .setSaveConsumer { draft = draft.copy(minDiscountPct = it) }
                .build()
        )

        general.addEntry(
            entries
                .startIntField(Text.literal("Min samples"), current.minSamples)
                .setDefaultValue(5)
                .setMin(1)
                .setMax(500)
                .setSaveConsumer { draft = draft.copy(minSamples = it) }
                .build()
        )

        general.addEntry(
            entries
                .startBooleanToggle(Text.literal("Dev mode enabled"), current.devModeEnabled)
                .setDefaultValue(false)
                .setSaveConsumer { draft = draft.copy(devModeEnabled = it) }
                .build()
        )

        general.addEntry(
            entries
                .startStrField(Text.literal("Export directory"), current.exportDir)
                .setDefaultValue("config/donutsauctions/exports")
                .setSaveConsumer { draft = draft.copy(exportDir = it) }
                .build()
        )

        automation.addEntry(
            entries
                .startBooleanToggle(Text.literal("Idle background enabled"), current.idleBackgroundEnabled)
                .setDefaultValue(false)
                .setSaveConsumer { draft = draft.copy(idleBackgroundEnabled = it) }
                .build()
        )

        automation.addEntry(
            entries
                .startIntField(Text.literal("Idle start delay (ms)"), current.idleStartDelayMs.toInt())
                .setDefaultValue(3_000)
                .setMin(500)
                .setMax(60_000)
                .setSaveConsumer { draft = draft.copy(idleStartDelayMs = it.toLong()) }
                .build()
        )

        automation.addEntry(
            entries
                .startIntField(Text.literal("General scan pages"), current.generalScanPages)
                .setDefaultValue(20)
                .setMin(1)
                .setMax(100)
                .setSaveConsumer { draft = draft.copy(generalScanPages = it) }
                .build()
        )

        automation.addEntry(
            entries
                .startIntField(Text.literal("Watch scan pages"), current.watchScanPages)
                .setDefaultValue(2)
                .setMin(1)
                .setMax(20)
                .setSaveConsumer { draft = draft.copy(watchScanPages = it) }
                .build()
        )

        automation.addEntry(
            entries
                .startIntField(Text.literal("Watch items per cycle"), current.watchItemsPerWindowCap)
                .setDefaultValue(3)
                .setMin(1)
                .setMax(20)
                .setSaveConsumer { draft = draft.copy(watchItemsPerWindowCap = it) }
                .build()
        )

        automation.addEntry(
            entries
                .startIntField(Text.literal("Page dwell (ms)"), current.pageDwellMs.toInt())
                .setDefaultValue(500)
                .setMin(100)
                .setMax(5_000)
                .setSaveConsumer { draft = draft.copy(pageDwellMs = it.toLong()) }
                .build()
        )

        automation.addEntry(
            entries
                .startIntField(Text.literal("Watch cycle interval (ms)"), current.watchCycleIntervalMs.toInt())
                .setDefaultValue(60_000)
                .setMin(5_000)
                .setMax(600_000)
                .setSaveConsumer { draft = draft.copy(watchCycleIntervalMs = it.toLong()) }
                .build()
        )

        automation.addEntry(
            entries
                .startIntField(Text.literal("General cycle interval (ms)"), current.generalCycleIntervalMs.toInt())
                .setDefaultValue(120_000)
                .setMin(5_000)
                .setMax(600_000)
                .setSaveConsumer { draft = draft.copy(generalCycleIntervalMs = it.toLong()) }
                .build()
        )

        automation.addEntry(
            entries
                .startIntField(Text.literal("Command min interval (ms)"), current.commandMinIntervalMs.toInt())
                .setDefaultValue(2_500)
                .setMin(500)
                .setMax(60_000)
                .setSaveConsumer { draft = draft.copy(commandMinIntervalMs = it.toLong()) }
                .build()
        )

        automation.addEntry(
            entries
                .startIntField(Text.literal("Page capture timeout (ms)"), current.pageCaptureTimeoutMs.toInt())
                .setDefaultValue(2_500)
                .setMin(500)
                .setMax(15_000)
                .setSaveConsumer { draft = draft.copy(pageCaptureTimeoutMs = it.toLong()) }
                .build()
        )

        builder.setSavingRunnable {
            DonutsAuctionsClient.configManager.save(draft)
            DonutsAuctionsClient.reloadConfiguration()
        }

        return builder.build()
    }
}
