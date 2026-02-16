package de.lukas.donutsauctions.db

import de.lukas.donutsauctions.model.AhSearchContext
import de.lukas.donutsauctions.model.AuctionListingRecord
import de.lukas.donutsauctions.model.TrackedMenuType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqliteAuctionRepositoryTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `inserts and reads best unit price`() {
        val repository = SqliteAuctionRepository(tempDir.resolve("auctions.db"))
        repository.initSchema()

        val snapshotId = repository.insertSnapshot(
            searchContext = AhSearchContext("/ah wooden axe", "wooden axe", 1000L, "test", TrackedMenuType.AUCTION),
            page = 1,
            sortMode = "Lowest Price",
            filterMode = "All",
            snapshotHash = "hash-1",
            rawTitle = "AUCTION (Page 1)"
        )

        val listings = listOf(
            record(
                capturedAt = 1000L,
                query = "wooden axe",
                slot = 0,
                itemId = "minecraft:wooden_axe",
                itemSignature = "sig-wooden-axe",
                name = "Wooden Axe",
                amount = 1,
                totalPrice = 1500L,
                unitPrice = 1500.0,
                seller = "A",
                snapshotHash = "hash-1"
            ),
            record(
                capturedAt = 1000L,
                query = "wooden axe",
                slot = 1,
                itemId = "minecraft:wooden_axe",
                itemSignature = "sig-wooden-axe",
                name = "Wooden Axe",
                amount = 2,
                totalPrice = 2000L,
                unitPrice = 1000.0,
                seller = "B",
                snapshotHash = "hash-1"
            )
        )

        repository.insertListings(snapshotId, listings)

        val best = repository.findBestUnitPrice("wooden axe", "minecraft:wooden_axe")
        assertNotNull(best)
        assertEquals(1000.0, best.unitPrice)
        assertEquals(2, best.amount)
        assertEquals("sig-wooden-axe", best.itemSignature)

        repository.close()
    }

    @Test
    fun `search and stats use lookback median and stack sizing`() {
        val repository = SqliteAuctionRepository(tempDir.resolve("analytics.db"))
        repository.initSchema()

        val now = System.currentTimeMillis()
        val old = now - 40L * 24L * 60L * 60L * 1000L

        val snapshotId = repository.insertSnapshot(
            searchContext = AhSearchContext("/ah redstone", "redstone", now, "test", TrackedMenuType.AUCTION),
            page = 1,
            sortMode = null,
            filterMode = null,
            snapshotHash = "hash-redstone",
            rawTitle = "AUCTION (Page 1)"
        )

        repository.insertListings(
            snapshotId,
            listOf(
                record(now - 10_000L, "redstone", 0, "minecraft:redstone", "sig-normal", "Redstone Dust", 1, 100L, 100.0, "s1", "hash-redstone"),
                record(now - 9_000L, "redstone", 1, "minecraft:redstone", "sig-normal", "Redstone Dust", 1, 120L, 120.0, "s2", "hash-redstone"),
                record(now - 8_000L, "redstone", 2, "minecraft:redstone", "sig-normal", "Redstone Dust", 1, 180L, 180.0, "s3", "hash-redstone"),
                record(old, "redstone", 3, "minecraft:redstone", "sig-normal", "Redstone Dust", 1, 1_000L, 1000.0, "old", "hash-redstone"),
                record(now - 7_000L, "redstone", 4, "minecraft:redstone", "sig-ench", "Redstone Dust", 1, 50L, 50.0, "s4", "hash-redstone"),
                record(now - 6_000L, "redstone", 5, "minecraft:redstone_lamp", "sig-lamp", "Redstone Lamp", 1, 200L, 200.0, "s5", "hash-redstone")
            )
        )

        val hits = repository.searchItems("minecraft:redstone", lookbackDays = 30, limit = 10)
        assertTrue(hits.isNotEmpty())
        assertEquals("minecraft:redstone", hits.first().itemId)
        assertEquals("sig-ench", hits.first().itemSignature)

        val stats = repository.getItemStats(
            itemId = "minecraft:redstone",
            itemSignature = "sig-normal",
            lookbackDays = 30,
            stackSize = 16
        )
        assertNotNull(stats)
        assertEquals(3, stats.sampleCount)
        assertDouble(100.0, stats.minUnitPrice)
        assertDouble(133.333333, stats.avgUnitPrice)
        assertDouble(120.0, stats.medianUnitPrice)
        assertDouble(1_600.0, stats.minStackPrice)
        assertDouble(2_133.333333, stats.avgStackPrice)
        assertDouble(1_920.0, stats.medianStackPrice)

        val cheapest = repository.getLowestListings(
            itemId = "minecraft:redstone",
            itemSignature = "sig-normal",
            lookbackDays = 30,
            limit = 5
        )
        assertEquals(3, cheapest.size)
        assertDouble(100.0, cheapest.first().unitPrice)

        repository.close()
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
