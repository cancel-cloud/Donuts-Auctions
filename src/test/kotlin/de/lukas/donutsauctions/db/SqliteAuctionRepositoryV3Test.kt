package de.lukas.donutsauctions.db

import de.lukas.donutsauctions.model.AhSearchContext
import de.lukas.donutsauctions.model.AuctionListingRecord
import de.lukas.donutsauctions.model.TrackedMenuType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqliteAuctionRepositoryV3Test {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `dedupe index ignores duplicate listing rows in same snapshot`() {
        val dbPath = tempDir.resolve("dedupe.db")
        val repository = SqliteAuctionRepository(dbPath)
        repository.initSchema()

        val snapshotId = repository.insertSnapshot(
            searchContext = AhSearchContext("/ah lever", "lever", 1000L, "test", TrackedMenuType.AUCTION),
            page = 1,
            sortMode = null,
            filterMode = null,
            snapshotHash = "hash-dup",
            rawTitle = "AUCTION (Page 1)"
        )

        val row = record(
            capturedAt = 1000L,
            query = "lever",
            slot = 5,
            itemId = "minecraft:lever",
            itemSignature = "sig-lever",
            name = "Lever",
            amount = 1,
            totalPrice = 1000L,
            unitPrice = 1000.0,
            seller = "sellerA",
            snapshotHash = "hash-dup"
        )

        repository.insertListings(snapshotId, listOf(row, row.copy(slot = 6)))

        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM ah_listings;").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt(1))
                }
            }
        }

        repository.close()
    }

    @Test
    fun `watchlist deals analytics and export work`() {
        val repository = SqliteAuctionRepository(tempDir.resolve("v3.db"))
        repository.initSchema()

        val now = System.currentTimeMillis()
        val snapshotId = repository.insertSnapshot(
            searchContext = AhSearchContext("/ah test", "test", now, "test", TrackedMenuType.AUCTION),
            page = 1,
            sortMode = null,
            filterMode = null,
            snapshotHash = "hash-v3",
            rawTitle = "AUCTION (Page 1)"
        )

        val rows = listOf(
            // item A (good deal)
            record(now - 1L * 60L * 60L * 1000L, "test", 1, "minecraft:lever", "sig-a", "Lever", 1, 100L, 100.0, "s1", "hash-v3"),
            record(now - 2L * 24L * 60L * 60L * 1000L, "test", 2, "minecraft:lever", "sig-a", "Lever", 1, 120L, 120.0, "s1", "hash-v3"),
            record(now - 5L * 24L * 60L * 60L * 1000L, "test", 3, "minecraft:lever", "sig-a", "Lever", 1, 140L, 140.0, "s2", "hash-v3"),
            record(now - 10L * 24L * 60L * 60L * 1000L, "test", 4, "minecraft:lever", "sig-a", "Lever", 1, 160L, 160.0, "s3", "hash-v3"),
            record(now - 20L * 24L * 60L * 60L * 1000L, "test", 5, "minecraft:lever", "sig-a", "Lever", 1, 180L, 180.0, "s3", "hash-v3"),

            // item B (not enough discount)
            record(now - 1L * 60L * 60L * 1000L, "test", 6, "minecraft:stick", "sig-b", "Stick", 1, 95L, 95.0, "u1", "hash-v3"),
            record(now - 2L * 24L * 60L * 60L * 1000L, "test", 7, "minecraft:stick", "sig-b", "Stick", 1, 100L, 100.0, "u2", "hash-v3"),
            record(now - 3L * 24L * 60L * 60L * 1000L, "test", 8, "minecraft:stick", "sig-b", "Stick", 1, 100L, 100.0, "u3", "hash-v3"),
            record(now - 6L * 24L * 60L * 60L * 1000L, "test", 9, "minecraft:stick", "sig-b", "Stick", 1, 100L, 100.0, "u4", "hash-v3"),
            record(now - 12L * 24L * 60L * 60L * 1000L, "test", 10, "minecraft:stick", "sig-b", "Stick", 1, 100L, 100.0, "u5", "hash-v3")
        )

        repository.insertListings(snapshotId, rows)

        repository.setWatchlist("minecraft:lever", "sig-a", "Lever", enabled = true)
        val watchlist = repository.listWatchlist()
        assertEquals(1, watchlist.size)

        val deals = repository.findBestDeals(
            lookbackDays = 30,
            minDiscountPct = 10.0,
            minSamples = 5,
            watchlistOnly = false,
            limit = 20
        )
        assertEquals(1, deals.size)
        assertEquals("minecraft:lever", deals.first().itemId)
        assertTrue(deals.first().discountVsMedianPct > 20.0)

        val dealsWatchOnly = repository.findBestDeals(
            lookbackDays = 30,
            minDiscountPct = 10.0,
            minSamples = 5,
            watchlistOnly = true,
            limit = 20
        )
        assertEquals(1, dealsWatchOnly.size)

        val trends = repository.getTrendStats("minecraft:lever", "sig-a")
        assertEquals(3, trends.windows.size)
        assertEquals("24h", trends.windows[0].windowLabel)
        assertEquals(1, trends.windows[0].sampleCount)

        val trend25h = repository.getTrendSeries("minecraft:lever", "sig-a", windowHours = 25, bucketMinutes = 60)
        val trend7d = repository.getTrendSeries("minecraft:lever", "sig-a", windowHours = 7 * 24, bucketMinutes = 360)
        val trend30d = repository.getTrendSeries("minecraft:lever", "sig-a", windowHours = 30 * 24, bucketMinutes = 1440)
        assertEquals(60, trend25h.bucketMinutes)
        assertEquals(360, trend7d.bucketMinutes)
        assertEquals(1440, trend30d.bucketMinutes)
        assertTrue(trend25h.points.isNotEmpty())
        assertTrue(trend7d.points.isNotEmpty())
        assertTrue(trend30d.points.isNotEmpty())
        assertTrue(trend25h.points.zipWithNext().all { (a, b) -> a.bucketStartAt <= b.bucketStartAt })

        val pct = repository.getPercentileStats("minecraft:lever", "sig-a", 30)
        assertNotNull(pct)
        assertEquals(5, pct.sampleCount)
        assertDouble(100.0, pct.p10)
        assertDouble(140.0, pct.p50)
        assertDouble(180.0, pct.p90)

        val sellers = repository.getSellerStats("minecraft:lever", "sig-a", 30, 10)
        assertTrue(sellers.isNotEmpty())
        assertEquals("s1", sellers.first().seller)

        val exportDir = tempDir.resolve("exports")
        val csv = repository.exportItemHistoryCsv("minecraft:lever", "sig-a", 30, exportDir)
        val json = repository.exportItemSummaryJson("minecraft:lever", "sig-a", 30, exportDir)
        assertNotNull(csv)
        assertNotNull(json)
        assertTrue(csv.toFile().exists())
        assertTrue(json.toFile().exists())

        val shulkerSnapshot = repository.insertSnapshot(
            searchContext = AhSearchContext("/ah shulker", "shulker", now, "test", TrackedMenuType.AUCTION),
            page = 1,
            sortMode = null,
            filterMode = null,
            snapshotHash = "hash-shulker-v3",
            rawTitle = "AUCTION (Page 1)"
        )
        repository.insertListings(
            shulkerSnapshot,
            listOf(
                record(now, "shulker", 11, "minecraft:shulker_box", "sig-shulker", "Shulker Box", 1, 1L, 1.0, "s", "hash-shulker-v3")
            )
        )
        repository.close()

        val reopened = SqliteAuctionRepository(tempDir.resolve("v3.db"))
        reopened.initSchema()
        val shulkerHits = reopened.searchItems("shulker", lookbackDays = 30, limit = 100)
        assertTrue(shulkerHits.none { it.itemId.contains("shulker_box") })
        reopened.close()
    }

    private fun record(
        capturedAt: Long,
        query: String,
        slot: Int,
        itemId: String,
        itemSignature: String,
        name: String,
        amount: Int,
        totalPrice: Long,
        unitPrice: Double,
        seller: String,
        snapshotHash: String
    ): AuctionListingRecord {
        return AuctionListingRecord(
            capturedAt = capturedAt,
            query = query,
            page = 1,
            slot = slot,
            itemId = itemId,
            itemSignature = itemSignature,
            name = name,
            amount = amount,
            totalPrice = totalPrice,
            unitPrice = unitPrice,
            seller = seller,
            rawLore = "Price: $$totalPrice",
            sortMode = null,
            filterMode = null,
            snapshotHash = snapshotHash
        )
    }

    private fun assertDouble(expected: Double, actual: Double, epsilon: Double = 0.0001) {
        assertTrue(abs(expected - actual) <= epsilon, "Expected $expected, got $actual")
    }
}
