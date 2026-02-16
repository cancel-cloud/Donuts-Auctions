package de.lukas.donutsauctions

import de.lukas.donutsauctions.config.DonutsAuctionsConfigManager
import de.lukas.donutsauctions.db.AuctionRepository
import de.lukas.donutsauctions.db.SqliteAuctionRepository
import de.lukas.donutsauctions.model.AhSearchContext
import de.lukas.donutsauctions.model.DealCandidate
import de.lukas.donutsauctions.model.ItemPercentileStats
import de.lukas.donutsauctions.model.ItemPriceStats
import de.lukas.donutsauctions.model.ItemSearchHit
import de.lukas.donutsauctions.model.ItemTrendSeries
import de.lukas.donutsauctions.model.ItemTrendStats
import de.lukas.donutsauctions.model.ListingPreview
import de.lukas.donutsauctions.model.MigrationSummary
import de.lukas.donutsauctions.model.RepositoryHealth
import de.lukas.donutsauctions.model.RepositoryStatus
import de.lukas.donutsauctions.model.SellerStatsRow
import de.lukas.donutsauctions.model.WatchlistItem
import de.lukas.donutsauctions.parser.DefaultCommandQueryParser
import de.lukas.donutsauctions.parser.PriceParser
import de.lukas.donutsauctions.scanner.AhCaptureResult
import de.lukas.donutsauctions.scanner.AhCaptureService
import de.lukas.donutsauctions.scanner.DefaultAhScreenClassifier
import de.lukas.donutsauctions.scanner.SnapshotDeduplicator
import de.lukas.donutsauctions.ui.DonutsAuctionsScreen
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory
import org.lwjgl.glfw.GLFW
import java.nio.file.Path

object DonutsAuctionsClient : ClientModInitializer {
    const val MOD_ID: String = "donutsauctions"
    private val logger = LoggerFactory.getLogger("DonutsAuctions")
    private const val SEARCH_CONTEXT_TTL_MS = 45_000L
    private const val NOOP_LOG_THROTTLE_MS = 5_000L

    lateinit var configManager: DonutsAuctionsConfigManager
        private set

    private var repository: AuctionRepository? = null
    private var captureService: AhCaptureService? = null
    private val commandParser = DefaultCommandQueryParser()
    private val deduplicator = SnapshotDeduplicator()

    private lateinit var openGuiKey: KeyBinding

    @Volatile
    private var pendingSearchContext: AhSearchContext? = null

    @Volatile
    private var activeSearchContext: AhSearchContext? = null

    @Volatile
    private var lastCaptureCount: Int = 0

    @Volatile
    private var lastCaptureAt: Long = 0L

    @Volatile
    private var bestDealSummary: String = "No data yet"

    @Volatile
    private var lastError: String? = null

    private var tickCounter: Long = 0
    private var housekeepingCounter: Long = 0
    private var auctionMenuOpen: Boolean = false
    private var lastNoopReason: String? = null
    private var lastNoopLoggedAt: Long = 0L

    private val unavailableHealth = RepositoryHealth(
        status = RepositoryStatus.ERROR,
        message = "Repository unavailable",
        readOnly = true,
        lastMigrationSummary = MigrationSummary.empty()
    )

    override fun onInitializeClient() {
        configManager = DonutsAuctionsConfigManager(
            FabricLoader.getInstance().configDir.resolve("donutsauctions.properties")
        )
        configManager.load()
        reloadConfiguration()

        openGuiKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.donutsauctions.open",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_KP_MULTIPLY,
                KeyBinding.Category.create(Identifier.of(MOD_ID, "general"))
            )
        )

        ClientSendMessageEvents.ALLOW_CHAT.register(ClientSendMessageEvents.AllowChat { message ->
            trackOutgoingMessage(message)
            true
        })
        ClientSendMessageEvents.ALLOW_COMMAND.register(ClientSendMessageEvents.AllowCommand { command ->
            trackOutgoingMessage("/$command")
            true
        })

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            while (openGuiKey.wasPressed()) {
                client.setScreen(DonutsAuctionsScreen())
            }
            runCaptureTick(client)
        })

        ClientLifecycleEvents.CLIENT_STOPPING.register(ClientLifecycleEvents.ClientStopping {
            repository?.close()
            repository = null
        })
    }

    @Synchronized
    fun reloadConfiguration() {
        val config = configManager.config
        val old = repository
        try {
            val newRepo = SqliteAuctionRepository(resolveDbPath(config.dbPath))
            newRepo.initSchema()

            repository = newRepo
            val health = newRepo.getHealth()
            captureService = if (!health.readOnly) {
                AhCaptureService(
                    repository = newRepo,
                    classifier = DefaultAhScreenClassifier(),
                    priceParser = PriceParser(),
                    deduplicator = deduplicator
                ).also { it.resetSession() }
            } else {
                null
            }

            old?.close()
            deduplicator.reset()
            lastError = if (health.status == RepositoryStatus.OK) null else health.message
            logger.info(
                "[DonutsAuctions][db] status={} readOnly={} message={}",
                health.status,
                health.readOnly,
                health.message
            )
        } catch (ex: Exception) {
            lastError = ex.message ?: ex.javaClass.simpleName
            logger.error("Failed to initialize DonutsAuctions repository", ex)
            if (repository == null) {
                captureService = null
            }
        }
    }

    private fun trackOutgoingMessage(rawMessage: String) {
        val parsed = commandParser.parseAhQuery(rawMessage) ?: return
        val context = parsed.copy(serverId = currentServerId())
        pendingSearchContext = context

        if (auctionMenuOpen && activeSearchContext?.menuType == context.menuType) {
            activeSearchContext = context
            deduplicator.reset()
            captureService?.resetSession()
            logDebug("Updated active ${context.menuType} session from command '${context.commandRaw}'")
        } else {
            captureService?.resetSession()
            logDebug("Registered command '${context.commandRaw}' for menu ${context.menuType}")
        }
    }

    private fun runCaptureTick(client: MinecraftClient) {
        val config = configManager.config
        if (!config.trackingEnabled) {
            return
        }

        tickCounter += 1
        if (tickCounter % config.scanIntervalTicks != 0L) {
            return
        }

        val handledScreen = client.currentScreen as? HandledScreen<*>
        if (handledScreen == null) {
            if (auctionMenuOpen) {
                endAuctionSession("menu closed")
            }
            return
        }

        val now = System.currentTimeMillis()
        val health = repositoryHealthSnapshot()
        if (health.readOnly) {
            logNoop("Capture disabled: ${health.message}", now)
            return
        }
        if (captureService == null) {
            logNoop("Capture service unavailable", now)
            return
        }

        val context = resolveContextForCapture(now) ?: return

        try {
            when (val result = captureService?.capture(client, context)) {
                is AhCaptureResult.Captured -> {
                    activateAuctionSession(context)
                    lastCaptureCount = result.listingCount
                    lastCaptureAt = result.capturedAt
                    lastNoopReason = null
                    logDebug("Captured ${result.listingCount} listings on page=${result.page ?: "unknown"} (hash=${result.snapshotHash.take(12)}...)")
                    refreshBestDealSummary()
                }
                is AhCaptureResult.Duplicate -> {
                    activateAuctionSession(context)
                    logDebug("Skipped duplicate snapshot")
                }
                is AhCaptureResult.Noop -> {
                    logNoop(result.reason, now)
                    if (auctionMenuOpen && result.reason.startsWith("Screen does not look like auction")) {
                        endAuctionSession("screen no longer recognized as auction")
                    }
                }
                null -> logNoop("Capture service unavailable", now)
            }

            housekeepingCounter += 1
            if (housekeepingCounter % 60L == 0L) {
                repository?.enforceMaxSize(config.maxDbSizeMb)
            }
            lastError = null
        } catch (ex: Exception) {
            lastError = ex.message ?: ex.javaClass.simpleName
            logger.error("Capture tick failed", ex)
        }
    }

    private fun refreshBestDealSummary() {
        val query = activeSearchContext?.query ?: pendingSearchContext?.query.orEmpty()
        if (query.isBlank()) {
            bestDealSummary = "No query context"
            return
        }

        val best = repository?.findBestUnitPrice(query, null)
        bestDealSummary = if (best != null && best.unitPrice != null) {
            "${best.name} @ ${"%.2f".format(best.unitPrice)}"
        } else {
            "No price data for '$query'"
        }
    }

    private fun resolveContextForCapture(now: Long): AhSearchContext? {
        activeSearchContext?.let { return it }

        val pending = pendingSearchContext ?: return null
        val ageMs = now - pending.timestamp
        if (ageMs > SEARCH_CONTEXT_TTL_MS) {
            logNoop("Ignoring stale command context (${ageMs}ms old)", now)
            pendingSearchContext = null
            return null
        }

        return pending.copy(serverId = currentServerId())
    }

    private fun activateAuctionSession(context: AhSearchContext) {
        if (auctionMenuOpen && activeSearchContext != null) {
            return
        }
        activeSearchContext = context
        pendingSearchContext = context
        auctionMenuOpen = true
        deduplicator.reset()
        logDebug("Activated ${context.menuType} capture session for '${context.commandRaw}'")
    }

    private fun endAuctionSession(reason: String) {
        logDebug("Ending auction session: $reason")
        auctionMenuOpen = false
        activeSearchContext = null
        pendingSearchContext = null
        deduplicator.reset()
        captureService?.resetSession()
        lastNoopReason = null
        lastNoopLoggedAt = 0L
    }

    private fun currentServerId(): String {
        return MinecraftClient.getInstance().currentServerEntry?.address ?: "singleplayer"
    }

    private fun logNoop(reason: String, now: Long) {
        val shouldLog = lastNoopReason != reason || (now - lastNoopLoggedAt) >= NOOP_LOG_THROTTLE_MS
        if (shouldLog) {
            logger.info("[DonutsAuctions][debug] capture noop: {}", reason)
            lastNoopReason = reason
            lastNoopLoggedAt = now
        }
    }

    private fun logDebug(message: String) {
        logger.info("[DonutsAuctions][debug] {}", message)
    }

    private fun resolveDbPath(rawDbPath: String): Path {
        val path = Path.of(rawDbPath)
        return if (path.isAbsolute) {
            path.normalize()
        } else {
            FabricLoader.getInstance().gameDir.resolve(path).normalize()
        }
    }

    data class RuntimeStateSnapshot(
        val trackingEnabled: Boolean,
        val lastQuery: String,
        val lastCaptureCount: Int,
        val lastCaptureAt: Long,
        val bestDealSummary: String,
        val lastError: String?,
        val repositoryStatus: RepositoryStatus,
        val repositoryMessage: String
    )

    fun runtimeStateSnapshot(): RuntimeStateSnapshot {
        val config = if (::configManager.isInitialized) configManager.config else null
        return RuntimeStateSnapshot(
            trackingEnabled = config?.trackingEnabled ?: false,
            lastQuery = activeSearchContext?.query ?: pendingSearchContext?.query.orEmpty(),
            lastCaptureCount = lastCaptureCount,
            lastCaptureAt = lastCaptureAt,
            bestDealSummary = bestDealSummary,
            lastError = lastError,
            repositoryStatus = repositoryHealthSnapshot().status,
            repositoryMessage = repositoryHealthSnapshot().message
        )
    }

    @Synchronized
    fun repositoryHealthSnapshot(): RepositoryHealth {
        return try {
            repository?.getHealth() ?: unavailableHealth
        } catch (ex: Exception) {
            lastError = ex.message ?: ex.javaClass.simpleName
            unavailableHealth.copy(message = "Repository error: ${lastError ?: "unknown"}")
        }
    }

    @Synchronized
    fun isRepositoryWritable(): Boolean {
        return !repositoryHealthSnapshot().readOnly
    }

    @Synchronized
    fun searchItems(term: String, lookbackDays: Int, limit: Int = 100): List<ItemSearchHit> {
        return try {
            val rows = repository?.searchItems(term, lookbackDays, limit).orEmpty()
            if (isDebugEnabled()) {
                logger.info(
                    "[DonutsAuctions][debug] ui search term='{}' lookbackDays={} limit={} -> {} hits",
                    term,
                    lookbackDays,
                    limit,
                    rows.size
                )
            }
            rows
        } catch (ex: Exception) {
            lastError = ex.message ?: ex.javaClass.simpleName
            logger.error("Failed to search local auction items", ex)
            emptyList()
        }
    }

    @Synchronized
    fun getItemStats(itemId: String, itemSignature: String, lookbackDays: Int, stackSize: Int): ItemPriceStats? {
        return try {
            val stats = repository?.getItemStats(itemId, itemSignature, lookbackDays, stackSize)
            if (isDebugEnabled()) {
                logger.info(
                    "[DonutsAuctions][debug] ui stats itemId={} sig={} lookbackDays={} stack={} -> {}",
                    itemId,
                    itemSignature.take(10),
                    lookbackDays,
                    stackSize,
                    if (stats == null) "none" else "samples=${stats.sampleCount}"
                )
            }
            stats
        } catch (ex: Exception) {
            lastError = ex.message ?: ex.javaClass.simpleName
            logger.error("Failed to read local item stats", ex)
            null
        }
    }

    @Synchronized
    fun getLowestListings(itemId: String, itemSignature: String, lookbackDays: Int, limit: Int = 20): List<ListingPreview> {
        return try {
            val rows = repository?.getLowestListings(itemId, itemSignature, lookbackDays, limit).orEmpty()
            if (isDebugEnabled()) {
                logger.info(
                    "[DonutsAuctions][debug] ui cheapest itemId={} sig={} lookbackDays={} limit={} -> {} rows",
                    itemId,
                    itemSignature.take(10),
                    lookbackDays,
                    limit,
                    rows.size
                )
            }
            rows
        } catch (ex: Exception) {
            lastError = ex.message ?: ex.javaClass.simpleName
            logger.error("Failed to read local listing previews", ex)
            emptyList()
        }
    }

    private fun isDebugEnabled(): Boolean {
        return ::configManager.isInitialized && configManager.config.debugLogging
    }

    @Synchronized
    fun findBestDeals(lookbackDays: Int, minDiscountPct: Double, minSamples: Int, watchlistOnly: Boolean, limit: Int = 150): List<DealCandidate> {
        return try {
            repository?.findBestDeals(lookbackDays, minDiscountPct, minSamples, watchlistOnly, limit).orEmpty()
        } catch (ex: Exception) {
            lastError = ex.message ?: ex.javaClass.simpleName
            logger.error("Failed to read best deals", ex)
            emptyList()
        }
    }

    @Synchronized
    fun setWatchlist(itemId: String, itemSignature: String, displayName: String, enabled: Boolean) {
        if (!isRepositoryWritable()) {
            lastError = "Repository is read-only; watchlist update blocked"
            return
        }
        try {
            repository?.setWatchlist(itemId, itemSignature, displayName, enabled)
        } catch (ex: Exception) {
            lastError = ex.message ?: ex.javaClass.simpleName
            logger.error("Failed to set watchlist", ex)
        }
    }

    @Synchronized
    fun listWatchlist(): List<WatchlistItem> {
        return try {
            repository?.listWatchlist().orEmpty()
        } catch (ex: Exception) {
            lastError = ex.message ?: ex.javaClass.simpleName
            logger.error("Failed to read watchlist", ex)
            emptyList()
        }
    }

    @Synchronized
    fun getTrendStats(itemId: String, itemSignature: String): ItemTrendStats? {
        return try {
            repository?.getTrendStats(itemId, itemSignature)
        } catch (ex: Exception) {
            lastError = ex.message ?: ex.javaClass.simpleName
            logger.error("Failed to read trend stats", ex)
            null
        }
    }

    @Synchronized
    fun getTrendSeries(itemId: String, itemSignature: String, windowHours: Int, bucketMinutes: Int): ItemTrendSeries? {
        return try {
            repository?.getTrendSeries(itemId, itemSignature, windowHours, bucketMinutes)
        } catch (ex: Exception) {
            lastError = ex.message ?: ex.javaClass.simpleName
            logger.error("Failed to read trend series", ex)
            null
        }
    }

    @Synchronized
    fun getPercentileStats(itemId: String, itemSignature: String, lookbackDays: Int): ItemPercentileStats? {
        return try {
            repository?.getPercentileStats(itemId, itemSignature, lookbackDays)
        } catch (ex: Exception) {
            lastError = ex.message ?: ex.javaClass.simpleName
            logger.error("Failed to read percentile stats", ex)
            null
        }
    }

    @Synchronized
    fun getSellerStats(itemId: String, itemSignature: String, lookbackDays: Int, limit: Int = 20): List<SellerStatsRow> {
        return try {
            repository?.getSellerStats(itemId, itemSignature, lookbackDays, limit).orEmpty()
        } catch (ex: Exception) {
            lastError = ex.message ?: ex.javaClass.simpleName
            logger.error("Failed to read seller stats", ex)
            emptyList()
        }
    }

    @Synchronized
    fun rebuildIndexes(): String {
        if (!isRepositoryWritable()) {
            return "Reindex skipped: repository is read-only"
        }
        try {
            repository?.rebuildIndexes()
            return "Database indexes rebuilt"
        } catch (ex: Exception) {
            lastError = ex.message ?: ex.javaClass.simpleName
            logger.error("Failed to rebuild indexes", ex)
            return "Reindex failed: ${lastError ?: "unknown"}"
        }
    }

    @Synchronized
    fun vacuumDatabase(): String {
        if (!isRepositoryWritable()) {
            return "Vacuum skipped: repository is read-only"
        }
        try {
            repository?.vacuum()
            return "Database VACUUM completed"
        } catch (ex: Exception) {
            lastError = ex.message ?: ex.javaClass.simpleName
            logger.error("Failed to vacuum database", ex)
            return "Vacuum failed: ${lastError ?: "unknown"}"
        }
    }

    @Synchronized
    fun exportItemHistoryCsv(itemId: String, itemSignature: String, lookbackDays: Int): Path? {
        return try {
            repository?.exportItemHistoryCsv(itemId, itemSignature, lookbackDays, resolveExportDir())
        } catch (ex: Exception) {
            lastError = ex.message ?: ex.javaClass.simpleName
            logger.error("Failed to export CSV", ex)
            null
        }
    }

    @Synchronized
    fun exportItemSummaryJson(itemId: String, itemSignature: String, lookbackDays: Int): Path? {
        return try {
            repository?.exportItemSummaryJson(itemId, itemSignature, lookbackDays, resolveExportDir())
        } catch (ex: Exception) {
            lastError = ex.message ?: ex.javaClass.simpleName
            logger.error("Failed to export JSON", ex)
            null
        }
    }

    @Synchronized
    fun setDevModeEnabled(enabled: Boolean) {
        if (!::configManager.isInitialized) return
        val updated = configManager.save(configManager.config.copy(devModeEnabled = enabled))
        if (isDebugEnabled()) {
            logger.info("[DonutsAuctions][debug] dev mode set to {}", updated.devModeEnabled)
        }
    }

    private fun resolveExportDir(): Path {
        val configured = if (::configManager.isInitialized) configManager.config.exportDir else "config/donutsauctions/exports"
        val path = Path.of(configured)
        return if (path.isAbsolute) {
            path.normalize()
        } else {
            FabricLoader.getInstance().gameDir.resolve(path).normalize()
        }
    }
}
