package de.lukas.donutsauctions.ui

import de.lukas.donutsauctions.DonutsAuctionsClient
import de.lukas.donutsauctions.config.DonutsAuctionsConfigScreenFactory
import de.lukas.donutsauctions.model.DealCandidate
import de.lukas.donutsauctions.model.ItemPercentileStats
import de.lukas.donutsauctions.model.ItemPriceStats
import de.lukas.donutsauctions.model.ItemSearchHit
import de.lukas.donutsauctions.model.ItemTrendSeries
import de.lukas.donutsauctions.model.ListingPreview
import de.lukas.donutsauctions.model.RepositoryStatus
import de.lukas.donutsauctions.model.SellerStatsRow
import de.lukas.donutsauctions.model.TrendSeriesPoint
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.client.input.KeyInput
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class DonutsAuctionsScreen : Screen(Text.literal("DonutsAuctions")) {

    private enum class ViewMode { SEARCH, DEALS }
    private enum class SortMode { BEST_DEAL, MIN_PRICE, SAMPLES, RECENT, NAME }
    private enum class FilterMode { ALL, WATCHLIST }
    private enum class DetailTab { OVERVIEW, TRENDS, SELLERS }
    private enum class TrendWindow(val label: String, val hours: Int, val bucketMinutes: Int) {
        H25("25h", 25, 60),
        D7("7d", 7 * 24, 360),
        D30("30d", 30 * 24, 1440)
    }
    private enum class EmptyState { NONE, NO_DATA, NO_MATCHES, BACKEND_UNAVAILABLE }

    private data class ResultRow(
        val itemId: String,
        val itemSignature: String,
        val displayName: String,
        val minUnitPrice: Double,
        val sampleCount: Int,
        val lastSeenAt: Long,
        val dealScore: Double?
    )

    private data class LayoutMetrics(
        val topControlsBottomY: Int,
        val statusBannerTopY: Int,
        val statusBannerBottomY: Int,
        val statusTextY: Int,
        val contentTopY: Int,
        val footerReservedHeight: Int,
        val devToolsReservedHeight: Int,
        val contentBottomY: Int,
        val showRepairLine: Boolean
    )

    private lateinit var searchField: TextFieldWidget
    private lateinit var searchButton: ButtonWidget
    private lateinit var modeButton: ButtonWidget
    private lateinit var refreshButton: ButtonWidget
    private lateinit var configButton: ButtonWidget

    private lateinit var sortButton: ButtonWidget
    private lateinit var filterButton: ButtonWidget
    private lateinit var lookbackButton: ButtonWidget
    private lateinit var stackButton: ButtonWidget

    private lateinit var tabOverviewButton: ButtonWidget
    private lateinit var tabTrendsButton: ButtonWidget
    private lateinit var tabSellersButton: ButtonWidget
    private lateinit var watchButton: ButtonWidget
    private lateinit var trend25hButton: ButtonWidget
    private lateinit var trend7dButton: ButtonWidget
    private lateinit var trend30dButton: ButtonWidget

    private lateinit var devToggleButton: ButtonWidget
    private lateinit var exportCsvButton: ButtonWidget
    private lateinit var exportJsonButton: ButtonWidget
    private lateinit var rebuildButton: ButtonWidget
    private lateinit var vacuumButton: ButtonWidget

    private val stackOptions = listOf(1, 16, 64)
    private var stackIndex = 0
    private var lookbackDays = 30

    private var viewMode = ViewMode.SEARCH
    private var sortMode = SortMode.MIN_PRICE
    private var filterMode = FilterMode.ALL
    private var detailTab = DetailTab.OVERVIEW
    private var devModeEnabled = false

    private var rows: List<ResultRow> = emptyList()
    private var selectedRow: ResultRow? = null
    private var selectedStats: ItemPriceStats? = null
    private var selectedListings: List<ListingPreview> = emptyList()
    private var selectedTrendSeries: ItemTrendSeries? = null
    private var selectedPercentiles: ItemPercentileStats? = null
    private var selectedSellers: List<SellerStatsRow> = emptyList()
    private var watchlistKeys: Set<String> = emptySet()
    private var trendWindow: TrendWindow = TrendWindow.H25

    private var statusLine = "Ready"
    private var emptyState = EmptyState.NONE
    private var resultScroll = 0

    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private val rowHeight: Int
        get() = DonutsAuctionsScreenLayoutMath.computeRowHeight(textRenderer.fontHeight)

    override fun init() {
        val compact = isCompactLayout()
        val margin = 20
        val gap = 6
        val rowAY = 34
        val rowBY = 60

        val searchWidth = if (compact) 180 else 280
        searchField = TextFieldWidget(textRenderer, margin, rowAY, searchWidth, 20, Text.literal("Search"))
        searchField.setMaxLength(120)

        var x = margin + searchWidth + gap
        searchButton = ButtonWidget.builder(Text.literal("Search")) { performSearch() }
            .dimensions(x, rowAY, if (compact) 78 else 88, 20)
            .build()
        x += if (compact) 84 else 94

        modeButton = ButtonWidget.builder(Text.literal("")) { cycleViewMode() }
            .dimensions(x, rowAY, if (compact) 154 else 172, 20)
            .build()
        x += if (compact) 160 else 178

        refreshButton = ButtonWidget.builder(Text.literal("Refresh")) { refreshData() }
            .dimensions(x, rowAY, if (compact) 92 else 100, 20)
            .build()

        configButton = ButtonWidget.builder(Text.literal(if (compact) "Config" else "Mod Config")) {
            client?.setScreen(DonutsAuctionsConfigScreenFactory.create(this))
        }.dimensions(width - if (compact) 96 else 118, rowAY, if (compact) 76 else 98, 20).build()

        sortButton = ButtonWidget.builder(Text.literal("")) { cycleSortMode() }
            .dimensions(margin, rowBY, if (compact) 128 else 138, 20)
            .build()
        filterButton = ButtonWidget.builder(Text.literal("")) { cycleFilterMode() }
            .dimensions(margin + if (compact) 134 else 144, rowBY, if (compact) 126 else 134, 20)
            .build()
        lookbackButton = ButtonWidget.builder(Text.literal("")) { toggleLookback() }
            .dimensions(margin + if (compact) 266 else 284, rowBY, if (compact) 128 else 138, 20)
            .build()
        stackButton = ButtonWidget.builder(Text.literal("")) { cycleStackSize() }
            .dimensions(margin + if (compact) 400 else 428, rowBY, if (compact) 120 else 128, 20)
            .build()

        val details = detailsPanelBounds()
        tabOverviewButton = ButtonWidget.builder(Text.literal("Overview")) { switchTab(DetailTab.OVERVIEW) }
            .dimensions(details.left + 8, details.top + 4, 74, 16)
            .build()
        tabTrendsButton = ButtonWidget.builder(Text.literal("Trends")) { switchTab(DetailTab.TRENDS) }
            .dimensions(details.left + 86, details.top + 4, 62, 16)
            .build()
        tabSellersButton = ButtonWidget.builder(Text.literal("Sellers")) { switchTab(DetailTab.SELLERS) }
            .dimensions(details.left + 152, details.top + 4, 62, 16)
            .build()
        watchButton = ButtonWidget.builder(Text.literal("☆ Watch")) { toggleWatchSelected() }
            .dimensions(details.right - 94, details.top + 4, 86, 16)
            .build()
        trend25hButton = ButtonWidget.builder(Text.literal("25h")) { switchTrendWindow(TrendWindow.H25) }
            .dimensions(details.left + 220, details.top + 4, 38, 16)
            .build()
        trend7dButton = ButtonWidget.builder(Text.literal("7d")) { switchTrendWindow(TrendWindow.D7) }
            .dimensions(details.left + 262, details.top + 4, 34, 16)
            .build()
        trend30dButton = ButtonWidget.builder(Text.literal("30d")) { switchTrendWindow(TrendWindow.D30) }
            .dimensions(details.left + 300, details.top + 4, 42, 16)
            .build()

        devToggleButton = ButtonWidget.builder(Text.literal("Dev")) { toggleDevMode() }
            .dimensions(width - 58, height - 26, 42, 16)
            .build()

        exportCsvButton = ButtonWidget.builder(Text.literal("CSV")) { exportCsv() }
            .dimensions(width - 262, height - 26, 46, 16)
            .build()
        exportJsonButton = ButtonWidget.builder(Text.literal("JSON")) { exportJson() }
            .dimensions(width - 212, height - 26, 52, 16)
            .build()
        rebuildButton = ButtonWidget.builder(Text.literal("Reindex")) { rebuildIndexes() }
            .dimensions(width - 156, height - 26, 64, 16)
            .build()
        vacuumButton = ButtonWidget.builder(Text.literal("Vacuum")) { vacuumDb() }
            .dimensions(width - 88, height - 26, 64, 16)
            .build()

        addDrawableChild(searchField)
        addDrawableChild(searchButton)
        addDrawableChild(modeButton)
        addDrawableChild(refreshButton)
        addDrawableChild(configButton)

        addDrawableChild(sortButton)
        addDrawableChild(filterButton)
        addDrawableChild(lookbackButton)
        addDrawableChild(stackButton)

        addDrawableChild(tabOverviewButton)
        addDrawableChild(tabTrendsButton)
        addDrawableChild(tabSellersButton)
        addDrawableChild(watchButton)
        addDrawableChild(trend25hButton)
        addDrawableChild(trend7dButton)
        addDrawableChild(trend30dButton)

        addDrawableChild(devToggleButton)
        addDrawableChild(exportCsvButton)
        addDrawableChild(exportJsonButton)
        addDrawableChild(rebuildButton)
        addDrawableChild(vacuumButton)

        updateFromConfig()
        updateButtonLabels()
        updateControlState()
        setInitialFocus(searchField)
        performSearch()
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val layout = computeLayoutMetrics()

        context.fill(0, 0, width, height, 0x88000000.toInt())
        context.drawText(textRenderer, title, 20, 14, color(0xFFFFFF), true)
        drawStatusBanner(context, layout)
        context.drawText(textRenderer, Text.literal(statusLine), 20, layout.statusTextY, color(0xD0D0D0), false)

        drawResultsPanel(context, mouseX, mouseY, layout)
        drawDetailPanel(context, layout)
        drawFooter(context, layout)
        super.render(context, mouseX, mouseY, delta)
    }

    override fun keyPressed(input: KeyInput): Boolean {
        val keyCode = input.key()
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            performSearch()
            return true
        }
        return super.keyPressed(input)
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (super.mouseClicked(click, doubled)) {
            return true
        }

        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false
        }

        val panel = resultsPanelBounds()
        val rowsTop = panel.top + 20
        val visibleRows = ((panel.height - 24) / rowHeight).coerceAtLeast(1)

        if (click.x() >= panel.left && click.x() <= panel.right && click.y() >= rowsTop && click.y() <= panel.bottom) {
            val row = ((click.y() - rowsTop) / rowHeight).toInt()
            val absoluteIndex = resultScroll + row
            if (absoluteIndex in rows.indices && row < visibleRows) {
                selectRow(absoluteIndex)
                return true
            }
        }

        return false
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val panel = resultsPanelBounds()
        if (mouseX < panel.left || mouseX > panel.right || mouseY < panel.top || mouseY > panel.bottom) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        }

        val visibleRows = ((panel.height - 24) / rowHeight).coerceAtLeast(1)
        val maxScroll = (rows.size - visibleRows).coerceAtLeast(0)
        if (maxScroll == 0) {
            return true
        }

        val direction = if (verticalAmount > 0) -1 else if (verticalAmount < 0) 1 else 0
        if (direction != 0) {
            resultScroll = (resultScroll + direction).coerceIn(0, maxScroll)
        }

        return true
    }

    override fun shouldPause(): Boolean = false

    private fun performSearch() {
        val health = DonutsAuctionsClient.repositoryHealthSnapshot()
        watchlistKeys = DonutsAuctionsClient.listWatchlist().map { keyOf(it.itemId, it.itemSignature) }.toSet()

        rows = when (viewMode) {
            ViewMode.SEARCH -> buildSearchRows()
            ViewMode.DEALS -> buildDealRows()
        }

        rows = applySort(rows)
        if (filterMode == FilterMode.WATCHLIST) {
            rows = rows.filter { isWatched(it.itemId, it.itemSignature) }
        }

        resultScroll = 0
        when {
            health.status == RepositoryStatus.ERROR -> {
                emptyState = EmptyState.BACKEND_UNAVAILABLE
                selectedRow = null
                clearDetailData()
                statusLine = health.message
                updateControlState()
                return
            }
            rows.isEmpty() -> {
                emptyState = if (searchField.text.trim().isBlank() && viewMode == ViewMode.SEARCH) {
                    EmptyState.NO_DATA
                } else {
                    EmptyState.NO_MATCHES
                }
                selectedRow = null
                clearDetailData()
                statusLine = when (emptyState) {
                    EmptyState.NO_DATA -> "No data in DB for current mode/window"
                    EmptyState.NO_MATCHES -> "No matches for '${searchField.text.trim()}'"
                    else -> "No data"
                }
                updateControlState()
                return
            }
            else -> {
                emptyState = EmptyState.NONE
            }
        }

        selectRow(0)
        statusLine = when (viewMode) {
            ViewMode.SEARCH -> "Found ${rows.size} local item variants"
            ViewMode.DEALS -> "Found ${rows.size} deal candidates"
        }
        updateControlState()
    }

    private fun refreshData() {
        val selectedKey = selectedRow?.let { keyOf(it.itemId, it.itemSignature) }
        performSearch()
        if (selectedKey != null) {
            val idx = rows.indexOfFirst { keyOf(it.itemId, it.itemSignature) == selectedKey }
            if (idx >= 0) {
                selectRow(idx)
            }
        }
    }

    private fun buildSearchRows(): List<ResultRow> {
        val query = searchField.text.trim()
        val lookback = if (lookbackDays == 0) 3650 else lookbackDays
        return DonutsAuctionsClient.searchItems(query, lookback, 250)
            .map { it.toRow() }
    }

    private fun buildDealRows(): List<ResultRow> {
        val config = DonutsAuctionsClient.configManager.config
        val deals = DonutsAuctionsClient.findBestDeals(
            lookbackDays = if (lookbackDays == 0) 30 else lookbackDays,
            minDiscountPct = config.minDiscountPct,
            minSamples = config.minSamples,
            watchlistOnly = filterMode == FilterMode.WATCHLIST,
            limit = 250
        )

        val query = searchField.text.trim().lowercase()
        return deals
            .filter { deal ->
                query.isBlank() || deal.displayName.lowercase().contains(query) || shortItemId(deal.itemId).contains(query)
            }
            .map { it.toRow() }
    }

    private fun applySort(input: List<ResultRow>): List<ResultRow> {
        return when (sortMode) {
            SortMode.BEST_DEAL -> input.sortedWith(
                compareByDescending<ResultRow> { it.dealScore ?: Double.NEGATIVE_INFINITY }
                    .thenBy { it.minUnitPrice }
            )
            SortMode.MIN_PRICE -> input.sortedBy { it.minUnitPrice }
            SortMode.SAMPLES -> input.sortedByDescending { it.sampleCount }
            SortMode.RECENT -> input.sortedByDescending { it.lastSeenAt }
            SortMode.NAME -> input.sortedBy { it.displayName.lowercase() }
        }
    }

    private fun selectRow(index: Int) {
        if (index !in rows.indices) return
        selectedRow = rows[index]

        val selected = selectedRow ?: return
        val lookback = if (lookbackDays == 0) 3650 else lookbackDays
        val trendLookback = if (lookbackDays == 0) 30 else lookbackDays

        selectedStats = DonutsAuctionsClient.getItemStats(selected.itemId, selected.itemSignature, lookback, currentStackSize())
        selectedListings = DonutsAuctionsClient.getLowestListings(selected.itemId, selected.itemSignature, lookback, 15)
        selectedTrendSeries = DonutsAuctionsClient.getTrendSeries(
            selected.itemId,
            selected.itemSignature,
            trendWindow.hours,
            trendWindow.bucketMinutes
        )
        selectedPercentiles = DonutsAuctionsClient.getPercentileStats(selected.itemId, selected.itemSignature, trendLookback)
        selectedSellers = DonutsAuctionsClient.getSellerStats(selected.itemId, selected.itemSignature, trendLookback, 30)

        updateControlState()
    }

    private fun clearDetailData() {
        selectedStats = null
        selectedListings = emptyList()
        selectedTrendSeries = null
        selectedPercentiles = null
        selectedSellers = emptyList()
    }

    private fun cycleViewMode() {
        viewMode = if (viewMode == ViewMode.SEARCH) ViewMode.DEALS else ViewMode.SEARCH
        performSearch()
        updateButtonLabels()
    }

    private fun cycleSortMode() {
        val values = SortMode.entries
        sortMode = values[(values.indexOf(sortMode) + 1) % values.size]
        rows = applySort(rows)
        updateButtonLabels()
    }

    private fun cycleFilterMode() {
        filterMode = if (filterMode == FilterMode.ALL) FilterMode.WATCHLIST else FilterMode.ALL
        performSearch()
        updateButtonLabels()
    }

    private fun toggleLookback() {
        lookbackDays = if (lookbackDays == 30) 0 else 30
        performSearch()
        updateButtonLabels()
    }

    private fun cycleStackSize() {
        stackIndex = (stackIndex + 1) % stackOptions.size
        val selected = selectedRow
        if (selected != null) {
            val lookback = if (lookbackDays == 0) 3650 else lookbackDays
            selectedStats = DonutsAuctionsClient.getItemStats(selected.itemId, selected.itemSignature, lookback, currentStackSize())
        }
        updateButtonLabels()
    }

    private fun switchTab(tab: DetailTab) {
        detailTab = tab
        updateButtonLabels()
        updateControlState()
    }

    private fun switchTrendWindow(window: TrendWindow) {
        trendWindow = window
        val selected = selectedRow
        if (selected != null) {
            selectedTrendSeries = DonutsAuctionsClient.getTrendSeries(
                selected.itemId,
                selected.itemSignature,
                trendWindow.hours,
                trendWindow.bucketMinutes
            )
        }
        updateButtonLabels()
        updateControlState()
    }

    private fun toggleWatchSelected() {
        val selected = selectedRow ?: return
        if (!DonutsAuctionsClient.isRepositoryWritable()) {
            statusLine = "Watchlist is disabled while DB is read-only"
            updateControlState()
            return
        }
        val watched = isWatched(selected.itemId, selected.itemSignature)
        DonutsAuctionsClient.setWatchlist(selected.itemId, selected.itemSignature, selected.displayName, !watched)
        watchlistKeys = DonutsAuctionsClient.listWatchlist().map { keyOf(it.itemId, it.itemSignature) }.toSet()
        if (filterMode == FilterMode.WATCHLIST) {
            performSearch()
        } else {
            updateControlState()
        }
    }

    private fun toggleDevMode() {
        devModeEnabled = !devModeEnabled
        DonutsAuctionsClient.setDevModeEnabled(devModeEnabled)
        updateButtonLabels()
        updateControlState()
    }

    private fun exportCsv() {
        val selected = selectedRow ?: run {
            statusLine = "Select an item first"
            return
        }
        val lookback = if (lookbackDays == 0) 3650 else lookbackDays
        val path = DonutsAuctionsClient.exportItemHistoryCsv(selected.itemId, selected.itemSignature, lookback)
        statusLine = if (path != null) {
            "CSV exported: ${compactPath(path)}"
        } else {
            "CSV export skipped: no rows"
        }
    }

    private fun exportJson() {
        val selected = selectedRow ?: run {
            statusLine = "Select an item first"
            return
        }
        val lookback = if (lookbackDays == 0) 3650 else lookbackDays
        val path = DonutsAuctionsClient.exportItemSummaryJson(selected.itemId, selected.itemSignature, lookback)
        statusLine = if (path != null) {
            "JSON exported: ${compactPath(path)}"
        } else {
            "JSON export skipped: no rows"
        }
    }

    private fun rebuildIndexes() {
        statusLine = DonutsAuctionsClient.rebuildIndexes()
    }

    private fun vacuumDb() {
        statusLine = DonutsAuctionsClient.vacuumDatabase()
    }

    private fun updateButtonLabels() {
        modeButton.message = Text.literal("Mode: ${if (viewMode == ViewMode.SEARCH) "Search" else "Deals"} (*)")
        sortButton.message = Text.literal("Sort: ${sortModeShort(sortMode)} (*)")
        filterButton.message = Text.literal("Filter: ${if (filterMode == FilterMode.ALL) "All" else "Watch"} (*)")
        lookbackButton.message = Text.literal("Window: ${if (lookbackDays == 0) "All" else "30d"} (*)")
        stackButton.message = Text.literal("Stack: x${currentStackSize()} (*)")

        tabOverviewButton.message = Text.literal(if (detailTab == DetailTab.OVERVIEW) "[Overview]" else "Overview")
        tabTrendsButton.message = Text.literal(if (detailTab == DetailTab.TRENDS) "[Trends]" else "Trends")
        tabSellersButton.message = Text.literal(if (detailTab == DetailTab.SELLERS) "[Sellers]" else "Sellers")
        trend25hButton.message = Text.literal(if (trendWindow == TrendWindow.H25) "[25h]" else "25h")
        trend7dButton.message = Text.literal(if (trendWindow == TrendWindow.D7) "[7d]" else "7d")
        trend30dButton.message = Text.literal(if (trendWindow == TrendWindow.D30) "[30d]" else "30d")

        devToggleButton.message = Text.literal("Dev")
    }

    private fun updateControlState() {
        val writable = DonutsAuctionsClient.isRepositoryWritable()
        val hasSelection = selectedRow != null

        sortButton.visible = true
        filterButton.visible = true
        lookbackButton.visible = true
        stackButton.visible = hasSelection

        val watchVisible = detailTab != DetailTab.TRENDS
        watchButton.visible = watchVisible
        watchButton.active = watchVisible && hasSelection && writable
        watchButton.message = when {
            !hasSelection -> Text.literal("☆ Watch")
            !writable -> Text.literal("☆ Watch")
            isWatched(selectedRow!!.itemId, selectedRow!!.itemSignature) -> Text.literal("★ Watched")
            else -> Text.literal("☆ Watch")
        }

        val trendButtonsVisible = detailTab == DetailTab.TRENDS
        trend25hButton.visible = trendButtonsVisible
        trend7dButton.visible = trendButtonsVisible
        trend30dButton.visible = trendButtonsVisible
        trend25hButton.active = trendButtonsVisible
        trend7dButton.active = trendButtonsVisible
        trend30dButton.active = trendButtonsVisible

        // Dev mode is controlled via Mod Config only.
        devToggleButton.visible = false
        devToggleButton.active = false

        val devVisible = devModeEnabled
        exportCsvButton.visible = devVisible
        exportJsonButton.visible = devVisible
        rebuildButton.visible = devVisible
        vacuumButton.visible = devVisible

        exportCsvButton.active = devVisible && hasSelection
        exportJsonButton.active = devVisible && hasSelection
        rebuildButton.active = devVisible && writable
        vacuumButton.active = devVisible && writable
    }

    private fun updateFromConfig() {
        val config = DonutsAuctionsClient.configManager.config
        devModeEnabled = config.devModeEnabled
    }

    private fun drawStatusBanner(context: DrawContext, layout: LayoutMetrics) {
        val health = DonutsAuctionsClient.repositoryHealthSnapshot()

        val (fillColor, title, textColor, message) = when (health.status) {
            RepositoryStatus.OK -> Quad(0x66314D31, "DB OK", 0xB7FFB7, "Writable. Capture enabled.")
            RepositoryStatus.READ_ONLY -> Quad(0x66533E1D, "DB Read-only", 0xFFD37F, health.message.ifBlank { "Write operations disabled." })
            RepositoryStatus.ERROR -> Quad(0x664D1E1E, "DB Error", 0xFF9E9E, health.message.ifBlank { "Backend unavailable." })
        }

        context.fill(20, layout.statusBannerTopY, width - 20, layout.statusBannerBottomY, fillColor)
        context.fill(20, layout.statusBannerTopY, width - 20, layout.statusBannerTopY + 1, 0x88FFFFFF.toInt())

        context.drawText(textRenderer, Text.literal(title), 26, layout.statusBannerTopY + 6, color(textColor), false)
        drawClampedText(
            context,
            message,
            106,
            layout.statusBannerTopY + 6,
            (width - 20) - 106 - 8,
            color(0xD8D8D8)
        )
    }

    private fun drawResultsPanel(context: DrawContext, mouseX: Int, mouseY: Int, layout: LayoutMetrics) {
        val panel = resultsPanelBounds(layout)
        drawPanel(context, panel.left, panel.top, panel.right, panel.bottom)
        context.drawText(textRenderer, Text.literal("Results"), panel.left + 6, panel.top + 6, color(0xFFFFFF), false)

        if (panel.height < rowHeight + 28) {
            context.drawText(textRenderer, Text.literal("Window too small for results"), panel.left + 6, panel.top + 24, color(0xB8B8B8), false)
            return
        }

        if (emptyState != EmptyState.NONE) {
            val text = when (emptyState) {
                EmptyState.NO_DATA -> "No data in local DB yet"
                EmptyState.NO_MATCHES -> "No matches for this query"
                EmptyState.BACKEND_UNAVAILABLE -> "Backend unavailable (see DB status above)"
                EmptyState.NONE -> ""
            }
            context.drawText(textRenderer, Text.literal(text), panel.left + 6, panel.top + 26, color(0xB8B8B8), false)
            return
        }

        val rowsTop = panel.top + 20
        val visibleRows = ((panel.height - 24) / rowHeight).coerceAtLeast(1)
        val endExclusive = (resultScroll + visibleRows).coerceAtMost(rows.size)

        withScissor(context, panel.left + 2, rowsTop, panel.right - 2, panel.bottom - 14) {
            for ((row, idx) in (resultScroll until endExclusive).withIndex()) {
                val y = rowsTop + (row * rowHeight)
                val item = rows[idx]
                val selected = selectedRow?.let { keyOf(it.itemId, it.itemSignature) == keyOf(item.itemId, item.itemSignature) } ?: false

                val bg = when {
                    selected -> 0x66486EA8
                    mouseX in panel.left..panel.right && mouseY in y..(y + rowHeight - 2) -> 0x332A2A2A
                    else -> 0x22141414
                }
                context.fill(panel.left + 2, y, panel.right - 2, y + rowHeight - 2, bg)

                val iconY = y + ((rowHeight - 16) / 2).coerceAtLeast(0)
                context.drawItem(resolveItemIcon(item.itemId), panel.left + 6, iconY)
                val watchPrefix = if (isWatched(item.itemId, item.itemSignature)) "★ " else ""
                drawClampedText(
                    context,
                    watchPrefix + item.displayName,
                    panel.left + 26,
                    y + 3,
                    panel.width - 34,
                    color(0xF0F0F0)
                )

                val subtitle = if (item.dealScore != null) {
                    "deal ${formatPercent(item.dealScore)}  |  min ${formatCoins(item.minUnitPrice)}  |  n=${item.sampleCount}"
                } else {
                    "${shortItemId(item.itemId)}  |  min ${formatCoins(item.minUnitPrice)}  |  n=${item.sampleCount}"
                }
                drawClampedText(
                    context,
                    subtitle,
                    panel.left + 26,
                    y + 3 + textRenderer.fontHeight + 2,
                    panel.width - 34,
                    color(0xAFC7E4)
                )
            }
        }

        if (rows.isNotEmpty()) {
            val visibleRangeEnd = (resultScroll + visibleRows).coerceAtMost(rows.size)
            context.drawText(
                textRenderer,
                Text.literal("${resultScroll + 1}-${visibleRangeEnd} / ${rows.size}"),
                panel.left + 6,
                panel.bottom - 12,
                color(0xA0A0A0),
                false
            )
        }
    }

    private fun drawDetailPanel(context: DrawContext, layout: LayoutMetrics) {
        val panel = detailsPanelBounds(layout)
        drawPanel(context, panel.left, panel.top, panel.right, panel.bottom)
        val tabStripBottom = panel.top + 24

        if (panel.height < 110) {
            context.drawText(textRenderer, Text.literal("Window too small for details"), panel.left + 10, panel.top + 28, color(0xA0A0A0), false)
            return
        }

        val selected = selectedRow
        if (selected == null) {
            context.drawText(textRenderer, Text.literal("Select an item from the list"), panel.left + 10, panel.top + 28, color(0xA0A0A0), false)
            return
        }

        withScissor(context, panel.left + 2, tabStripBottom + 2, panel.right - 2, panel.bottom - 2) {
            when (detailTab) {
                DetailTab.OVERVIEW -> drawOverviewTab(context, panel, selected, tabStripBottom + 2)
                DetailTab.TRENDS -> drawTrendsTab(context, panel, selected, tabStripBottom + 2)
                DetailTab.SELLERS -> drawSellersTab(context, panel, selected, tabStripBottom + 2)
            }
        }
    }

    private fun drawOverviewTab(context: DrawContext, panel: PanelBounds, selected: ResultRow, contentTop: Int) {
        val left = panel.left + 8
        val right = panel.right - 8
        val contentWidth = (right - left).coerceAtLeast(1)
        var cursorY = contentTop + 4

        drawSectionCard(context, left, cursorY, right, cursorY + 38, "Item")
        context.drawItem(resolveItemIcon(selected.itemId), left + 6, cursorY + 16)
        drawClampedText(context, selected.displayName, left + 26, cursorY + 16, contentWidth - 34, color(0xF0F0F0))
        drawClampedText(context, shortItemId(selected.itemId), left + 26, cursorY + 26, contentWidth - 34, color(0x9FB4CF))
        cursorY += 44

        val stats = selectedStats
        if (stats == null) {
            context.drawText(textRenderer, Text.literal("No priced samples in this window"), left, cursorY + 8, color(0xFF9E9E), false)
            return
        }

        val overviewTop = cursorY
        val overviewHeight = 84
        drawSectionCard(context, left, overviewTop, right, overviewTop + overviewHeight, "Overview")
        val twoColumn = DonutsAuctionsScreenLayoutMath.useTwoColumnOverview(contentWidth)

        if (twoColumn) {
            val leftLabelX = left + 8
            val leftValueX = left + 88
            val rightLabelX = left + (contentWidth / 2) + 8
            val rightValueX = rightLabelX + 78
            val leftValueWidth = (rightLabelX - leftValueX - 12).coerceAtLeast(40)
            val rightValueWidth = (right - rightValueX - 8).coerceAtLeast(40)
            var y = overviewTop + 16

            context.drawText(textRenderer, Text.literal("Samples"), leftLabelX, y, color(0xAFC7E4), false)
            drawClampedText(context, stats.sampleCount.toString(), leftValueX, y, leftValueWidth, color(0xEAEAEA))
            context.drawText(textRenderer, Text.literal("Last seen"), rightLabelX, y, color(0xAFC7E4), false)
            drawClampedText(context, formatTimestamp(stats.lastSeenAt), rightValueX, y, rightValueWidth, color(0xEAEAEA))
            y += 14

            context.drawText(textRenderer, Text.literal("Unit min"), leftLabelX, y, color(0xAFC7E4), false)
            drawClampedText(context, formatCoins(stats.minUnitPrice), leftValueX, y, leftValueWidth, color(0x9EE09E))
            context.drawText(textRenderer, Text.literal("Stack min"), rightLabelX, y, color(0xAFC7E4), false)
            drawClampedText(context, formatCoins(stats.minStackPrice), rightValueX, y, rightValueWidth, color(0xFFD37F))
            y += 14

            context.drawText(textRenderer, Text.literal("Unit avg"), leftLabelX, y, color(0xAFC7E4), false)
            drawClampedText(context, formatCoins(stats.avgUnitPrice), leftValueX, y, leftValueWidth, color(0x9EE09E))
            context.drawText(textRenderer, Text.literal("Stack avg"), rightLabelX, y, color(0xAFC7E4), false)
            drawClampedText(context, formatCoins(stats.avgStackPrice), rightValueX, y, rightValueWidth, color(0xFFD37F))
            y += 14

            context.drawText(textRenderer, Text.literal("Unit med"), leftLabelX, y, color(0xAFC7E4), false)
            drawClampedText(context, formatCoins(stats.medianUnitPrice), leftValueX, y, leftValueWidth, color(0x9EE09E))
            context.drawText(textRenderer, Text.literal("Stack x${stats.stackSize}"), rightLabelX, y, color(0xAFC7E4), false)
            drawClampedText(context, formatCoins(stats.medianStackPrice), rightValueX, y, rightValueWidth, color(0xFFD37F))
        } else {
            val lx = left + 8
            val vx = left + 94
            val width = (right - vx - 8).coerceAtLeast(40)
            var y = overviewTop + 16

            context.drawText(textRenderer, Text.literal("Samples"), lx, y, color(0xAFC7E4), false)
            drawClampedText(context, stats.sampleCount.toString(), vx, y, width, color(0xEAEAEA))
            y += 12
            context.drawText(textRenderer, Text.literal("Last seen"), lx, y, color(0xAFC7E4), false)
            drawClampedText(context, formatTimestamp(stats.lastSeenAt), vx, y, width, color(0xEAEAEA))
            y += 12
            context.drawText(textRenderer, Text.literal("Unit min"), lx, y, color(0xAFC7E4), false)
            drawClampedText(context, formatCoins(stats.minUnitPrice), vx, y, width, color(0x9EE09E))
            y += 12
            context.drawText(textRenderer, Text.literal("Unit avg"), lx, y, color(0xAFC7E4), false)
            drawClampedText(context, formatCoins(stats.avgUnitPrice), vx, y, width, color(0x9EE09E))
            y += 12
            context.drawText(textRenderer, Text.literal("Unit med"), lx, y, color(0xAFC7E4), false)
            drawClampedText(context, formatCoins(stats.medianUnitPrice), vx, y, width, color(0x9EE09E))
            y += 12
            context.drawText(textRenderer, Text.literal("Stack x${stats.stackSize}"), lx, y, color(0xAFC7E4), false)
            drawClampedText(context, formatCoins(stats.medianStackPrice), vx, y, width, color(0xFFD37F))
        }

        cursorY = overviewTop + overviewHeight + 4
        val pct = selectedPercentiles
        if (pct != null) {
            val percentileText = "p10 ${formatCoins(pct.p10)}  p50 ${formatCoins(pct.p50)}  p90 ${formatCoins(pct.p90)}"
            val line1 = trimToWidth(percentileText, contentWidth - 16)
            drawClampedText(context, line1, left + 8, cursorY, contentWidth - 16, color(0xC7DDF6))
            if (line1 != percentileText) {
                val remainder = percentileText.removePrefix(line1.removeSuffix("...")).trim()
                drawClampedText(context, remainder, left + 8, cursorY + 10, contentWidth - 16, color(0xC7DDF6))
                cursorY += 20
            } else {
                cursorY += 12
            }
            cursorY += 4
        }

        val listTop = cursorY
        drawSectionCard(context, left, listTop, right, panel.bottom - 8, "Cheapest Listings")

        val tableLeft = left + 8
        val tableRight = right - 8
        val tableWidth = (tableRight - tableLeft).coerceAtLeast(120)
        val colUnit = tableLeft
        val colAmount = tableLeft + (tableWidth * 0.30).toInt().coerceAtLeast(74)
        val colSeller = tableLeft + (tableWidth * 0.50).toInt().coerceAtLeast(120)
        val colAge = tableRight - 46

        val yHeader = listTop + 16
        context.drawText(textRenderer, Text.literal("Unit"), colUnit, yHeader, color(0xC9D5EA), false)
        context.drawText(textRenderer, Text.literal("Amount"), colAmount, yHeader, color(0xC9D5EA), false)
        context.drawText(textRenderer, Text.literal("Seller"), colSeller, yHeader, color(0xC9D5EA), false)
        context.drawText(textRenderer, Text.literal("Age"), colAge, yHeader, color(0xC9D5EA), false)

        var y = yHeader + 12
        val maxRows = ((panel.bottom - 12 - y) / 10).coerceAtLeast(1)
        selectedListings.take(maxRows).forEach { row ->
            drawClampedText(context, formatCoins(row.unitPrice), colUnit, y, (colAmount - colUnit - 6).coerceAtLeast(24), color(0xE4E4E4))
            drawClampedText(context, "${row.amount}x", colAmount, y, (colSeller - colAmount - 6).coerceAtLeast(20), color(0xE4E4E4))
            drawClampedText(context, row.seller ?: "?", colSeller, y, (colAge - colSeller - 6).coerceAtLeast(20), color(0xE4E4E4))
            drawClampedText(context, relativeAge(row.capturedAt), colAge, y, (tableRight - colAge).coerceAtLeast(20), color(0xE4E4E4))
            y += 10
        }
    }

    private fun drawTrendsTab(context: DrawContext, panel: PanelBounds, selected: ResultRow, contentTop: Int) {
        val left = panel.left + 8
        val right = panel.right - 8
        val contentWidth = (right - left).coerceAtLeast(1)

        drawSectionCard(context, left, contentTop + 4, right, contentTop + 42, "Item")
        drawClampedText(context, selected.displayName, left + 6, contentTop + 20, contentWidth - 12, color(0xF0F0F0))
        drawClampedText(context, shortItemId(selected.itemId), left + 6, contentTop + 30, contentWidth - 12, color(0x95A7C6))

        val series = selectedTrendSeries
        if (series == null || series.points.isEmpty()) {
            context.drawText(textRenderer, Text.literal("Trend data unavailable for ${trendWindow.label}"), left, contentTop + 56, color(0xFF9E9E), false)
            return
        }

        val graphTop = contentTop + 48
        val graphBottom = (graphTop + 128).coerceAtMost(panel.bottom - 110)
        drawSectionCard(context, left, graphTop, right, graphBottom, "Trend Graph (${series.windowLabel})")
        drawTrendGraph(context, left + 8, graphTop + 16, right - 8, graphBottom - 8, series.points)

        val rawTop = graphBottom + 4
        drawSectionCard(context, left, rawTop, right, panel.bottom - 8, "Raw Data (${series.windowLabel})")

        val tableLeft = left + 8
        val tableRight = right - 8
        val tableWidth = (tableRight - tableLeft).coerceAtLeast(140)
        val colTime = tableLeft
        val colMin = tableLeft + (tableWidth * 0.38).toInt().coerceAtLeast(120)
        val colMedian = tableLeft + (tableWidth * 0.58).toInt().coerceAtLeast(210)
        val colSamples = tableRight - 56

        var y = rawTop + 16
        context.drawText(textRenderer, Text.literal("Time"), colTime, y, color(0xC9D5EA), false)
        context.drawText(textRenderer, Text.literal("Min"), colMin, y, color(0xC9D5EA), false)
        context.drawText(textRenderer, Text.literal("Median"), colMedian, y, color(0xC9D5EA), false)
        context.drawText(textRenderer, Text.literal("Samples"), colSamples, y, color(0xC9D5EA), false)
        y += 12

        val maxRows = ((panel.bottom - 12 - y) / 10).coerceAtLeast(1)
        series.points.takeLast(maxRows).reversed().forEach { point ->
            drawClampedText(context, formatTimestamp(point.bucketStartAt), colTime, y, (colMin - colTime - 6).coerceAtLeast(30), color(0xE4E4E4))
            drawClampedText(context, point.minUnitPrice?.let { formatCoins(it) } ?: "n/a", colMin, y, (colMedian - colMin - 6).coerceAtLeast(24), color(0xE4E4E4))
            drawClampedText(context, point.medianUnitPrice?.let { formatCoins(it) } ?: "n/a", colMedian, y, (colSamples - colMedian - 6).coerceAtLeast(24), color(0xE4E4E4))
            drawClampedText(context, point.sampleCount.toString(), colSamples, y, (tableRight - colSamples).coerceAtLeast(16), color(0xE4E4E4))
            y += 10
        }
    }

    private fun drawSellersTab(context: DrawContext, panel: PanelBounds, selected: ResultRow, contentTop: Int) {
        val left = panel.left + 8
        val right = panel.right - 8
        val contentWidth = (right - left).coerceAtLeast(1)

        drawSectionCard(context, left, contentTop + 4, right, contentTop + 42, "Item")
        drawClampedText(context, selected.displayName, left + 6, contentTop + 20, contentWidth - 12, color(0xF0F0F0))
        drawClampedText(context, shortItemId(selected.itemId), left + 6, contentTop + 30, contentWidth - 12, color(0x95A7C6))

        drawSectionCard(context, left, contentTop + 48, right, panel.bottom - 8, "Seller Stats")

        var y = contentTop + 66
        val compact = contentWidth < 470
        if (!compact) {
            val colSeller = left + 8
            val colCount = left + (contentWidth * 0.35).toInt().coerceAtLeast(166)
            val colMin = left + (contentWidth * 0.49).toInt().coerceAtLeast(224)
            val colAvg = left + (contentWidth * 0.64).toInt().coerceAtLeast(290)
            val colLast = left + (contentWidth * 0.79).toInt().coerceAtLeast(354)

            context.drawText(textRenderer, Text.literal("Seller"), colSeller, y, color(0xC9D5EA), false)
            context.drawText(textRenderer, Text.literal("Count"), colCount, y, color(0xC9D5EA), false)
            context.drawText(textRenderer, Text.literal("Min"), colMin, y, color(0xC9D5EA), false)
            context.drawText(textRenderer, Text.literal("Avg"), colAvg, y, color(0xC9D5EA), false)
            context.drawText(textRenderer, Text.literal("Last"), colLast, y, color(0xC9D5EA), false)
            y += 12

            if (selectedSellers.isEmpty()) {
                context.drawText(textRenderer, Text.literal("No seller stats available"), left + 8, y, color(0xA0A0A0), false)
                return
            }

            val maxRows = ((panel.bottom - 12 - y) / 10).coerceAtLeast(1)
            selectedSellers.take(maxRows).forEach { s ->
                drawClampedText(context, s.seller, colSeller, y, (colCount - colSeller - 6).coerceAtLeast(20), color(0xE4E4E4))
                drawClampedText(context, s.listingCount.toString(), colCount, y, (colMin - colCount - 6).coerceAtLeast(20), color(0xE4E4E4))
                drawClampedText(context, formatCoins(s.minUnitPrice), colMin, y, (colAvg - colMin - 6).coerceAtLeast(20), color(0xE4E4E4))
                drawClampedText(context, formatCoins(s.avgUnitPrice), colAvg, y, (colLast - colAvg - 6).coerceAtLeast(20), color(0xE4E4E4))
                drawClampedText(context, formatTimestamp(s.lastSeenAt), colLast, y, (right - colLast - 6).coerceAtLeast(20), color(0xE4E4E4))
                y += 10
            }
        } else {
            if (selectedSellers.isEmpty()) {
                context.drawText(textRenderer, Text.literal("No seller stats available"), left + 8, y, color(0xA0A0A0), false)
                return
            }
            val maxRows = ((panel.bottom - 12 - y) / 20).coerceAtLeast(1)
            selectedSellers.take(maxRows).forEach { s ->
                drawClampedText(context, "${s.seller}  |  n=${s.listingCount}  |  min ${formatCoins(s.minUnitPrice)}", left + 8, y, contentWidth - 16, color(0xE4E4E4))
                y += 10
                drawClampedText(context, "avg ${formatCoins(s.avgUnitPrice)}  |  last ${formatTimestamp(s.lastSeenAt)}", left + 8, y, contentWidth - 16, color(0xBFCFE5))
                y += 12
            }
        }
    }

    private fun drawTrendGraph(context: DrawContext, left: Int, top: Int, right: Int, bottom: Int, points: List<TrendSeriesPoint>) {
        if (right - left < 40 || bottom - top < 30 || points.isEmpty()) {
            return
        }

        context.fill(left, top, right, bottom, 0x22000000)
        context.fill(left, bottom - 1, right, bottom, 0x66B0C7E8)
        context.fill(left, top, left + 1, bottom, 0x66B0C7E8)

        val medians = points.mapNotNull { it.medianUnitPrice }
        if (medians.isEmpty()) {
            drawClampedText(context, "No median data", left + 6, top + 6, right - left - 12, color(0xBDBDBD))
            return
        }
        val mins = points.mapNotNull { it.minUnitPrice }
        val minValue = (mins + medians).minOrNull() ?: return
        val maxValue = (mins + medians).maxOrNull() ?: return
        val range = (maxValue - minValue).takeIf { it > 0.0001 } ?: 1.0

        val plotWidth = (right - left - 2).coerceAtLeast(1)
        val plotHeight = (bottom - top - 2).coerceAtLeast(1)
        fun xFor(index: Int): Int {
            if (points.size == 1) return left + 1
            val ratio = index.toDouble() / (points.lastIndex.toDouble())
            return left + 1 + (ratio * (plotWidth - 1)).toInt()
        }
        fun yFor(value: Double): Int {
            val ratio = ((value - minValue) / range).coerceIn(0.0, 1.0)
            return top + 1 + ((1.0 - ratio) * (plotHeight - 1)).toInt()
        }

        var prevMedianX = -1
        var prevMinX = -1
        points.forEachIndexed { index, point ->
            val x = xFor(index)
            val median = point.medianUnitPrice
            if (median != null) {
                val y = yFor(median)
                if (prevMedianX >= 0) {
                    context.drawHorizontalLine(prevMedianX, x, y, color(0xE4B364))
                }
                context.fill(x, y, x + 1, y + 1, color(0xE4B364))
                prevMedianX = x
            }

            val min = point.minUnitPrice
            if (min != null) {
                val y = yFor(min)
                if (prevMinX >= 0) {
                    context.drawHorizontalLine(prevMinX, x, y, color(0xA7D7FF))
                }
                context.fill(x, y, x + 1, y + 1, color(0x9FD7FF))
                prevMinX = x
            }
        }

        drawClampedText(context, "max ${formatCoins(maxValue)}", left + 4, top + 3, right - left - 8, color(0x9FD7FF))
        drawClampedText(context, "min ${formatCoins(minValue)}", left + 4, bottom - 11, right - left - 8, color(0x9FD7FF))
        drawClampedText(
            context,
            "${formatTimestamp(points.first().bucketStartAt)} -> ${formatTimestamp(points.last().bucketStartAt)}",
            left + 4,
            bottom - 21,
            right - left - 8,
            color(0x9BB6D7)
        )
    }

    private fun drawFooter(context: DrawContext, layout: LayoutMetrics) {
        val health = DonutsAuctionsClient.repositoryHealthSnapshot()
        val foot = when {
            devModeEnabled && !DonutsAuctionsClient.isRepositoryWritable() -> "Dev mode ON • DB read-only (maintenance disabled)"
            viewMode == ViewMode.DEALS -> {
                val cfg = DonutsAuctionsClient.configManager.config
                "Deals mode • minDiscount ${formatPercent(cfg.minDiscountPct)} • minSamples ${cfg.minSamples}"
            }
            else -> "Search mode"
        }

        var y = height - layout.footerReservedHeight + 8
        if (layout.showRepairLine && health.lastMigrationSummary != null) {
            val summary = health.lastMigrationSummary
            context.drawText(
                textRenderer,
                Text.literal("repair: dup ${summary.duplicatesRemoved}, shulker ${summary.shulkerRowsRemoved}, legacy ${summary.legacyRowsRemoved}, orphan ${summary.orphanSnapshotsRemoved}"),
                20,
                y,
                color(0x7D8DA7),
                false
            )
            y += textRenderer.fontHeight + 2
        }

        context.drawText(textRenderer, Text.literal(foot), 20, y, color(0x8FA0BA), false)
    }

    private fun drawPanel(context: DrawContext, left: Int, top: Int, right: Int, bottom: Int) {
        context.fill(left, top, right, bottom, 0x88202020.toInt())
        context.fill(left + 1, top + 1, right - 1, bottom - 1, 0xAA101010.toInt())
    }

    private fun drawSectionCard(context: DrawContext, left: Int, top: Int, right: Int, bottom: Int, title: String) {
        context.fill(left, top, right, bottom, 0x3A1F2734)
        context.fill(left, top, right, top + 1, 0x8F637A9B.toInt())
        context.drawText(textRenderer, Text.literal(title), left + 6, top + 5, color(0xDDE8FF), false)
    }

    private inline fun withScissor(context: DrawContext, left: Int, top: Int, right: Int, bottom: Int, block: () -> Unit) {
        val scLeft = left.coerceAtLeast(0)
        val scTop = top.coerceAtLeast(0)
        val scRight = right.coerceAtMost(width)
        val scBottom = bottom.coerceAtMost(height)
        if (scRight <= scLeft || scBottom <= scTop) {
            return
        }
        context.enableScissor(scLeft, scTop, scRight, scBottom)
        try {
            block()
        } finally {
            context.disableScissor()
        }
    }

    private fun drawClampedText(context: DrawContext, value: String, x: Int, y: Int, maxWidth: Int, color: Int, shadow: Boolean = false) {
        val text = trimToWidth(value, maxWidth)
        context.drawText(textRenderer, Text.literal(text), x, y, color, shadow)
    }

    private fun resultsPanelBounds(layout: LayoutMetrics = computeLayoutMetrics()): PanelBounds {
        val left = 16
        val top = layout.contentTopY
        val right = (width / 2) - 6
        val bottom = layout.contentBottomY
        return PanelBounds(left, top, right, bottom)
    }

    private fun detailsPanelBounds(layout: LayoutMetrics = computeLayoutMetrics()): PanelBounds {
        val left = (width / 2) + 2
        val top = layout.contentTopY
        val right = width - 16
        val bottom = layout.contentBottomY
        return PanelBounds(left, top, right, bottom)
    }

    private fun computeLayoutMetrics(): LayoutMetrics {
        val health = DonutsAuctionsClient.repositoryHealthSnapshot()
        val showRepairLine = health.status != RepositoryStatus.OK && health.lastMigrationSummary != null

        val topControlsBottomY = 84
        val statusBannerTopY = topControlsBottomY
        val statusBannerBottomY = statusBannerTopY + 20
        val statusTextY = statusBannerBottomY + 8
        val contentTopY = statusTextY + textRenderer.fontHeight + 8

        val devToolsReservedHeight = if (devModeEnabled) 22 else 0
        val footerReservedHeight = DonutsAuctionsScreenLayoutMath.computeFooterReservedHeight(textRenderer.fontHeight, showRepairLine)

        val rawBottom = height - footerReservedHeight - devToolsReservedHeight - 6
        val minBottom = contentTopY + 100
        val contentBottomY = rawBottom.coerceAtLeast(minBottom).coerceAtMost(height - 12)

        return LayoutMetrics(
            topControlsBottomY = topControlsBottomY,
            statusBannerTopY = statusBannerTopY,
            statusBannerBottomY = statusBannerBottomY,
            statusTextY = statusTextY,
            contentTopY = contentTopY,
            footerReservedHeight = footerReservedHeight,
            devToolsReservedHeight = devToolsReservedHeight,
            contentBottomY = contentBottomY,
            showRepairLine = showRepairLine
        )
    }

    private fun currentStackSize(): Int = stackOptions[stackIndex]

    private fun resolveItemIcon(itemId: String): ItemStack {
        val id = Identifier.tryParse(itemId)
        if (id == null || !Registries.ITEM.containsId(id)) {
            return ItemStack(Items.BARRIER)
        }
        return ItemStack(Registries.ITEM.get(id))
    }

    private fun shortItemId(itemId: String): String = itemId.substringAfter(':', itemId)

    private fun cleanDisplayName(name: String, itemId: String): String {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            return prettyId(shortItemId(itemId))
        }
        if (trimmed.contains(":") && !trimmed.contains(' ')) {
            return prettyId(trimmed.substringAfter(':'))
        }
        return trimmed
    }

    private fun prettyId(raw: String): String {
        return raw.replace('_', ' ').split(' ').joinToString(" ") { token ->
            token.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
            }
        }
    }

    private fun sortModeShort(mode: SortMode): String {
        return when (mode) {
            SortMode.BEST_DEAL -> "Best"
            SortMode.MIN_PRICE -> "Min"
            SortMode.SAMPLES -> "Samples"
            SortMode.RECENT -> "Recent"
            SortMode.NAME -> "Name"
        }
    }

    private fun compactPath(path: Path): String {
        val raw = path.toString()
        return if (raw.length <= 70) raw else "...${raw.takeLast(67)}"
    }

    private fun formatCoins(value: Double): String {
        return "$" + String.format(Locale.US, "%,.2f", value)
    }

    private fun formatPercent(value: Double): String {
        return String.format(Locale.US, "%.1f%%", value)
    }

    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0L) return "n/a"
        return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(formatter)
    }

    private fun relativeAge(timestamp: Long): String {
        if (timestamp <= 0L) return "n/a"
        val diff = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
        val minutes = diff / 60_000L
        val hours = minutes / 60L
        val days = hours / 24L
        return when {
            days > 0 -> "${days}d"
            hours > 0 -> "${hours}h"
            minutes > 0 -> "${minutes}m"
            else -> "now"
        }
    }

    private fun trimToWidth(value: String, maxWidth: Int): String {
        if (maxWidth <= 0) return ""
        if (textRenderer.getWidth(value) <= maxWidth) return value

        var text = value
        while (text.isNotEmpty() && textRenderer.getWidth("$text...") > maxWidth) {
            text = text.dropLast(1)
        }
        return if (text.isEmpty()) "" else "$text..."
    }

    private fun color(rgb: Int): Int {
        return rgb or (0xFF shl 24)
    }

    private fun keyOf(itemId: String, itemSignature: String): String = "$itemId|$itemSignature"

    private fun isWatched(itemId: String, itemSignature: String): Boolean {
        return watchlistKeys.contains(keyOf(itemId, itemSignature))
    }

    private fun isCompactLayout(): Boolean {
        return width < 1320
    }

    private fun ItemSearchHit.toRow(): ResultRow {
        return ResultRow(
            itemId = itemId,
            itemSignature = itemSignature,
            displayName = cleanDisplayName(displayName, itemId),
            minUnitPrice = minUnitPrice,
            sampleCount = sampleCount,
            lastSeenAt = lastSeenAt,
            dealScore = null
        )
    }

    private fun DealCandidate.toRow(): ResultRow {
        return ResultRow(
            itemId = itemId,
            itemSignature = itemSignature,
            displayName = cleanDisplayName(displayName, itemId),
            minUnitPrice = currentMinUnitPrice,
            sampleCount = sampleCount30d,
            lastSeenAt = lastSeenAt,
            dealScore = discountVsMedianPct
        )
    }

    private data class PanelBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    private data class Quad<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )
}

internal object DonutsAuctionsScreenLayoutMath {
    fun computeRowHeight(fontHeight: Int): Int {
        return maxOf(24, fontHeight * 2 + 6)
    }

    fun computeFooterReservedHeight(fontHeight: Int, showRepairLine: Boolean): Int {
        val lines = if (showRepairLine) 2 else 1
        return 8 + (lines * (fontHeight + 2)) + 8
    }

    fun useTwoColumnOverview(contentWidth: Int): Boolean {
        return contentWidth >= 430
    }
}
