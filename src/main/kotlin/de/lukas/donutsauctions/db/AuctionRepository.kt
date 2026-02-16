package de.lukas.donutsauctions.db

import de.lukas.donutsauctions.model.AhSearchContext
import de.lukas.donutsauctions.model.AuctionListingRecord
import de.lukas.donutsauctions.model.DealCandidate
import de.lukas.donutsauctions.model.ItemPriceStats
import de.lukas.donutsauctions.model.ItemPercentileStats
import de.lukas.donutsauctions.model.ItemSearchHit
import de.lukas.donutsauctions.model.ItemTrendSeries
import de.lukas.donutsauctions.model.ItemTrendStats
import de.lukas.donutsauctions.model.ListingPreview
import de.lukas.donutsauctions.model.MigrationSummary
import de.lukas.donutsauctions.model.RepositoryHealth
import de.lukas.donutsauctions.model.SellerStatsRow
import de.lukas.donutsauctions.model.WatchlistItem
import java.nio.file.Path

interface AuctionRepository : AutoCloseable {
    fun initSchema()

    fun migrateAndRepair(): MigrationSummary

    fun getHealth(): RepositoryHealth

    fun insertSnapshot(
        searchContext: AhSearchContext?,
        page: Int?,
        sortMode: String?,
        filterMode: String?,
        snapshotHash: String,
        rawTitle: String
    ): Long

    fun insertListings(snapshotId: Long, listings: List<AuctionListingRecord>)

    fun findBestUnitPrice(query: String, itemId: String? = null): AuctionListingRecord?

    fun searchItems(term: String, lookbackDays: Int, limit: Int = 100): List<ItemSearchHit>

    fun getItemStats(itemId: String, itemSignature: String, lookbackDays: Int, stackSize: Int): ItemPriceStats?

    fun getLowestListings(itemId: String, itemSignature: String, lookbackDays: Int, limit: Int = 20): List<ListingPreview>

    fun findBestDeals(
        lookbackDays: Int,
        minDiscountPct: Double,
        minSamples: Int,
        watchlistOnly: Boolean,
        limit: Int = 100
    ): List<DealCandidate>

    fun setWatchlist(itemId: String, itemSignature: String, displayName: String, enabled: Boolean)

    fun listWatchlist(): List<WatchlistItem>

    fun getTrendStats(itemId: String, itemSignature: String): ItemTrendStats

    fun getTrendSeries(itemId: String, itemSignature: String, windowHours: Int, bucketMinutes: Int): ItemTrendSeries

    fun getPercentileStats(itemId: String, itemSignature: String, lookbackDays: Int): ItemPercentileStats?

    fun getSellerStats(itemId: String, itemSignature: String, lookbackDays: Int, limit: Int = 20): List<SellerStatsRow>

    fun rebuildIndexes()

    fun vacuum()

    fun exportItemHistoryCsv(itemId: String, itemSignature: String, lookbackDays: Int, outputDir: Path): Path?

    fun exportItemSummaryJson(itemId: String, itemSignature: String, lookbackDays: Int, outputDir: Path): Path?

    fun enforceMaxSize(maxSizeMb: Int)
}
