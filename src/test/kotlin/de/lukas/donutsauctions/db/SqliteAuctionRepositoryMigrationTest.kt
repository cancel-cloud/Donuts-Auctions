package de.lukas.donutsauctions.db

import de.lukas.donutsauctions.model.RepositoryStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqliteAuctionRepositoryMigrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `migrates old schema and removes legacy signature rows`() {
        val dbPath = tempDir.resolve("legacy.db")

        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE ah_snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        captured_at INTEGER NOT NULL,
                        query TEXT NOT NULL,
                        command_raw TEXT,
                        server_id TEXT,
                        page INTEGER,
                        sort_mode TEXT,
                        filter_mode TEXT,
                        snapshot_hash TEXT NOT NULL UNIQUE,
                        raw_title TEXT NOT NULL
                    );
                """.trimIndent())
                stmt.execute("""
                    CREATE TABLE ah_listings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        snapshot_id INTEGER NOT NULL,
                        captured_at INTEGER NOT NULL,
                        query TEXT NOT NULL,
                        page INTEGER,
                        slot INTEGER NOT NULL,
                        item_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        total_price INTEGER,
                        unit_price REAL,
                        seller TEXT,
                        raw_lore TEXT NOT NULL,
                        sort_mode TEXT,
                        filter_mode TEXT,
                        snapshot_hash TEXT NOT NULL
                    );
                """.trimIndent())

                stmt.execute("""
                    INSERT INTO ah_snapshots(id, captured_at, query, snapshot_hash, raw_title)
                    VALUES (1, 1000, 'lever', 'hash-1', 'AUCTION (Page 1)');
                """.trimIndent())

                stmt.execute("""
                    INSERT INTO ah_listings(
                        snapshot_id, captured_at, query, page, slot, item_id, name, amount,
                        total_price, unit_price, seller, raw_lore, sort_mode, filter_mode, snapshot_hash
                    ) VALUES (
                        1, 1000, 'lever', 1, 10, 'minecraft:lever', 'Lever', 1,
                        990, 990.0, 'einstein_coins', 'Price: $990', 'Lowest Price', 'All', 'hash-1'
                    );
                """.trimIndent())
            }
        }

        val repository = SqliteAuctionRepository(dbPath)
        repository.initSchema()

        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { stmt ->
                val columns = mutableListOf<String>()
                stmt.executeQuery("PRAGMA table_info(ah_listings);").use { rs ->
                    while (rs.next()) {
                        columns.add(rs.getString("name"))
                    }
                }

                assertFalse(columns.contains("page"))
                assertFalse(columns.contains("slot"))
                assertFalse(columns.contains("sort_mode"))
                assertFalse(columns.contains("filter_mode"))
                assertTrue(columns.containsAll(listOf("query", "item_id", "item_signature", "name", "amount", "snapshot_hash")))

                stmt.executeQuery("SELECT COUNT(*) FROM ah_listings;").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(0, rs.getInt(1))
                }

                stmt.executeQuery("SELECT COUNT(*) FROM ah_snapshots;").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(0, rs.getInt(1))
                }
            }
        }

        repository.close()
    }

    @Test
    fun `repairs duplicate rows before creating unique dedupe index`() {
        val dbPath = tempDir.resolve("dupes.db")

        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE ah_snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        captured_at INTEGER NOT NULL,
                        query TEXT NOT NULL,
                        command_raw TEXT,
                        server_id TEXT,
                        page INTEGER,
                        sort_mode TEXT,
                        filter_mode TEXT,
                        snapshot_hash TEXT NOT NULL UNIQUE,
                        raw_title TEXT NOT NULL
                    );
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE ah_listings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        snapshot_id INTEGER NOT NULL,
                        captured_at INTEGER NOT NULL,
                        query TEXT NOT NULL,
                        item_id TEXT NOT NULL,
                        item_signature TEXT NOT NULL DEFAULT '',
                        name TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        total_price INTEGER,
                        unit_price REAL,
                        seller TEXT,
                        raw_lore TEXT NOT NULL,
                        snapshot_hash TEXT NOT NULL
                    );
                    """.trimIndent()
                )

                stmt.execute(
                    """
                    INSERT INTO ah_snapshots(id, captured_at, query, snapshot_hash, raw_title)
                    VALUES (1, 1000, 'lever', 'hash-dup', 'AUCTION (Page 1)');
                    """.trimIndent()
                )

                stmt.execute(
                    """
                    INSERT INTO ah_listings(
                        id, snapshot_id, captured_at, query, item_id, item_signature, name, amount,
                        total_price, unit_price, seller, raw_lore, snapshot_hash
                    ) VALUES
                    (1, 1, 1000, 'lever', 'minecraft:lever', 'sig-a', 'Lever', 1, 1500, 1500.0, 'sellerA', 'line', 'hash-dup'),
                    (2, 1, 2000, 'lever', 'minecraft:lever', 'sig-a', 'Lever', 1, 1500, 1500.0, 'sellerA', 'line', 'hash-dup');
                    """.trimIndent()
                )
            }
        }

        val repository = SqliteAuctionRepository(dbPath)
        repository.initSchema()

        val health = repository.getHealth()
        assertEquals(RepositoryStatus.OK, health.status)
        assertFalse(health.readOnly)
        assertNotNull(health.lastMigrationSummary)
        assertTrue(health.lastMigrationSummary.duplicatesRemoved >= 1)
        assertTrue(health.lastMigrationSummary.shulkerRowsRemoved >= 0)

        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """
                    SELECT count(*) FROM (
                        SELECT snapshot_id, item_id, item_signature, coalesce(total_price,-1), amount, coalesce(seller,'') AS seller_key, count(*) c
                        FROM ah_listings
                        GROUP BY 1,2,3,4,5,6
                        HAVING c > 1
                    );
                    """.trimIndent()
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals(0, rs.getInt(1))
                }

                stmt.executeQuery("SELECT captured_at FROM ah_listings;").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(2000L, rs.getLong(1))
                }

                stmt.executeQuery("PRAGMA index_list('ah_listings');").use { rs ->
                    var uniqueIndexPresent = false
                    while (rs.next()) {
                        if (rs.getString("name") == "uq_listings_snapshot_row") {
                            uniqueIndexPresent = true
                        }
                    }
                    assertTrue(uniqueIndexPresent)
                }
            }
        }

        repository.close()
    }

    @Test
    fun `migration removes shulker rows and keeps non shulker rows`() {
        val dbPath = tempDir.resolve("shulker-cleanup.db")
        val repository = SqliteAuctionRepository(dbPath)
        repository.initSchema()

        val snapshotId = repository.insertSnapshot(
            searchContext = null,
            page = 1,
            sortMode = null,
            filterMode = null,
            snapshotHash = "hash-shulker",
            rawTitle = "AUCTION (Page 1)"
        )
        repository.insertListings(
            snapshotId,
            listOf(
                row("minecraft:shulker_box", "sig-a", "Shulker Box", "hash-shulker"),
                row("minecraft:red_shulker_box", "sig-b", "Red Shulker Box", "hash-shulker"),
                row("minecraft:iron_ingot", "sig-c", "Iron Ingot", "hash-shulker")
            )
        )
        repository.close()

        val reopened = SqliteAuctionRepository(dbPath)
        reopened.initSchema()
        val summary = reopened.getHealth().lastMigrationSummary
        assertNotNull(summary)
        assertTrue(summary.shulkerRowsRemoved >= 2)

        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM ah_listings WHERE lower(item_id) LIKE '%shulker_box%';").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(0, rs.getInt(1))
                }
                stmt.executeQuery("SELECT COUNT(*) FROM ah_listings WHERE item_id = 'minecraft:iron_ingot';").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt(1))
                }
            }
        }
        reopened.close()
    }

    private fun row(
        itemId: String,
        signature: String,
        name: String,
        hash: String
    ): de.lukas.donutsauctions.model.AuctionListingRecord {
        return de.lukas.donutsauctions.model.AuctionListingRecord(
            capturedAt = System.currentTimeMillis(),
            query = "",
            page = 1,
            slot = 1,
            itemId = itemId,
            itemSignature = signature,
            name = name,
            amount = 1,
            totalPrice = 10L,
            unitPrice = 10.0,
            seller = "seller",
            rawLore = "Price: $10",
            sortMode = null,
            filterMode = null,
            snapshotHash = hash
        )
    }
}
