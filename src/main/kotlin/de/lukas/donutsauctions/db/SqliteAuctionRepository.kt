package de.lukas.donutsauctions.db

import de.lukas.donutsauctions.model.AhSearchContext
import de.lukas.donutsauctions.model.AuctionListingRecord
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
import de.lukas.donutsauctions.model.TrendSeriesPoint
import de.lukas.donutsauctions.model.TrendWindowStat
import de.lukas.donutsauctions.model.WatchlistItem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import org.slf4j.LoggerFactory
import kotlin.io.path.fileSize

class SqliteAuctionRepository(
    private val dbPath: Path
) : AuctionRepository {
    private val logger = LoggerFactory.getLogger("DonutsAuctions")

    private val expectedListingColumns = listOf(
        "id",
        "snapshot_id",
        "captured_at",
        "query",
        "item_id",
        "item_signature",
        "name",
        "amount",
        "total_price",
        "unit_price",
        "seller",
        "raw_lore",
        "snapshot_hash"
    )

    private var connection: Connection? = null
    @Volatile
    private var writesEnabled: Boolean = true
    @Volatile
    private var health: RepositoryHealth = RepositoryHealth(
        status = RepositoryStatus.OK,
        message = "Not initialized",
        readOnly = false,
        lastMigrationSummary = null
    )

    @Synchronized
    override fun initSchema() {
        val conn = ensureConnection()
        configureConnection(conn)

        try {
            val summary = migrateAndRepair()
            writesEnabled = true
            health = RepositoryHealth(
                status = RepositoryStatus.OK,
                message = "DB OK",
                readOnly = false,
                lastMigrationSummary = summary
            )
            logger.info(
                "[DonutsAuctions][db] migration done: duplicatesRemoved={}, shulkerRemoved={}, legacyRemoved={}, orphansRemoved={}, snapshots={}, listings={}",
                summary.duplicatesRemoved,
                summary.shulkerRowsRemoved,
                summary.legacyRowsRemoved,
                summary.orphanSnapshotsRemoved,
                summary.finalSnapshotCount,
                summary.finalListingCount
            )
        } catch (ex: Exception) {
            writesEnabled = false
            val message = ex.message ?: ex.javaClass.simpleName
            health = RepositoryHealth(
                status = RepositoryStatus.READ_ONLY,
                message = "Migration failed; running read-only: $message",
                readOnly = true,
                lastMigrationSummary = null
            )
            logger.error("Migration failed; repository switched to read-only mode", ex)
            ensureBaseTables(conn)
            ensureReadOnlyCompatibility(conn)
        }
    }

    @Synchronized
    override fun migrateAndRepair(): MigrationSummary {
        val conn = ensureConnection()
        var backupPath: Path? = null
        var duplicatesRemoved = 0
        var shulkerRowsRemoved = 0
        var legacyRowsRemoved = 0
        var orphanSnapshotsRemoved = 0
        var vacuumed = false

        val previousAutoCommit = conn.autoCommit
        try {
            if (!previousAutoCommit) {
                conn.autoCommit = true
            }
            conn.createStatement().use { it.execute("BEGIN IMMEDIATE;") }

            ensureBaseTables(conn)
            ensureListingsTableSchema(conn)

            val duplicatesBefore = countDuplicateGroups(conn)
            val legacyBefore = countLegacyRows(conn)
            val shulkerBefore = countShulkerRows(conn)
            if (duplicatesBefore > 0 || legacyBefore > 0 || shulkerBefore > 0) {
                backupPath = backupDatabase()
            }

            duplicatesRemoved = removeDuplicateRows(conn)
            shulkerRowsRemoved = deleteShulkerRows(conn)
            legacyRowsRemoved = deleteLegacyRows(conn)
            orphanSnapshotsRemoved = pruneOrphanSnapshots(conn)

            createCoreIndexes(conn)

            conn.createStatement().use { it.execute("COMMIT;") }

            if (legacyRowsRemoved > 0 || duplicatesRemoved > 0 || shulkerRowsRemoved > 0 || orphanSnapshotsRemoved > 0) {
                ensureConnection().createStatement().use { it.execute("VACUUM;") }
                vacuumed = true
            }

            val finalCounts = countRows()
            return MigrationSummary(
                backupPath = backupPath?.toString(),
                duplicatesRemoved = duplicatesRemoved,
                shulkerRowsRemoved = shulkerRowsRemoved,
                legacyRowsRemoved = legacyRowsRemoved,
                orphanSnapshotsRemoved = orphanSnapshotsRemoved,
                finalSnapshotCount = finalCounts.first,
                finalListingCount = finalCounts.second,
                vacuumed = vacuumed
            )
        } catch (ex: Exception) {
            try {
                conn.createStatement().use { it.execute("ROLLBACK;") }
            } catch (_: Exception) {
            }
            throw ex
        } finally {
            try {
                conn.autoCommit = previousAutoCommit
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    override fun getHealth(): RepositoryHealth {
        return health
    }

    @Synchronized
    override fun insertSnapshot(
        searchContext: AhSearchContext?,
        page: Int?,
        sortMode: String?,
        filterMode: String?,
        snapshotHash: String,
        rawTitle: String
    ): Long {
        requireWritable("insertSnapshot")
        val conn = ensureConnection()
        conn.prepareStatement(
            """
            INSERT OR IGNORE INTO ah_snapshots(
                captured_at, query, command_raw, server_id, page, sort_mode, filter_mode, snapshot_hash, raw_title
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, System.currentTimeMillis())
            ps.setString(2, searchContext?.query.orEmpty())
            ps.setString(3, searchContext?.commandRaw)
            ps.setString(4, searchContext?.serverId)
            ps.setObject(5, page)
            ps.setString(6, sortMode)
            ps.setString(7, filterMode)
            ps.setString(8, snapshotHash)
            ps.setString(9, rawTitle)
            ps.executeUpdate()
        }

        conn.prepareStatement("SELECT id FROM ah_snapshots WHERE snapshot_hash = ?;").use { ps ->
            ps.setString(1, snapshotHash)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    return rs.getLong("id")
                }
            }
        }

        error("Failed to read snapshot id for hash=$snapshotHash")
    }

    @Synchronized
    override fun insertListings(snapshotId: Long, listings: List<AuctionListingRecord>) {
        requireWritable("insertListings")
        if (listings.isEmpty()) return

        val conn = ensureConnection()
        conn.prepareStatement(
            """
            INSERT OR IGNORE INTO ah_listings(
                snapshot_id, captured_at, query, item_id, item_signature, name, amount,
                total_price, unit_price, seller, raw_lore, snapshot_hash
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            listings.forEach { listing ->
                ps.setLong(1, snapshotId)
                ps.setLong(2, listing.capturedAt)
                ps.setString(3, listing.query)
                ps.setString(4, listing.itemId)
                ps.setString(5, listing.itemSignature)
                ps.setString(6, listing.name)
                ps.setInt(7, listing.amount)
                ps.setObject(8, listing.totalPrice)
                ps.setObject(9, listing.unitPrice)
                ps.setString(10, listing.seller)
                ps.setString(11, listing.rawLore)
                ps.setString(12, listing.snapshotHash)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    @Synchronized
    override fun findBestUnitPrice(query: String, itemId: String?): AuctionListingRecord? {
        val sql = buildString {
            append(
                """
                SELECT captured_at, query, item_id, item_signature, name, amount, total_price,
                       unit_price, seller, raw_lore, snapshot_hash
                FROM ah_listings
                WHERE query = ? AND unit_price IS NOT NULL
                """.trimIndent()
            )
            if (itemId != null) {
                append(" AND item_id = ?")
            }
            append(" ORDER BY unit_price ASC, captured_at DESC LIMIT 1")
        }

        val conn = ensureConnection()
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, query)
            if (itemId != null) {
                ps.setString(2, itemId)
            }
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    return mapListing(rs)
                }
            }
        }

        return null
    }

    @Synchronized
    override fun searchItems(term: String, lookbackDays: Int, limit: Int): List<ItemSearchHit> {
        val normalizedTerm = term.trim().lowercase()
        val fromTimestamp = lookbackLowerBoundMs(lookbackDays)
        val contains = "%$normalizedTerm%"
        val prefix = "$normalizedTerm%"

        val sql =
            """
            WITH filtered AS (
                SELECT item_id, item_signature, name, unit_price, captured_at
                FROM ah_listings
                WHERE unit_price IS NOT NULL
                  AND captured_at >= ?
                  AND (
                    ? = ''
                    OR lower(item_id) LIKE ?
                    OR lower(name) LIKE ?
                  )
            ), ranked AS (
                SELECT
                    item_id,
                    item_signature,
                    name,
                    row_number() OVER (PARTITION BY item_id, item_signature ORDER BY unit_price ASC, captured_at DESC) AS best_rank,
                    count(*) OVER (PARTITION BY item_id, item_signature) AS sample_count,
                    max(captured_at) OVER (PARTITION BY item_id, item_signature) AS last_seen_at,
                    min(unit_price) OVER (PARTITION BY item_id, item_signature) AS min_unit_price,
                    CASE
                        WHEN ? = '' THEN 0
                        WHEN lower(item_id) = ? THEN 0
                        WHEN lower(item_id) LIKE ? THEN 1
                        WHEN lower(item_id) LIKE ? THEN 2
                        WHEN lower(name) LIKE ? THEN 3
                        ELSE 4
                    END AS match_rank
                FROM filtered
            )
            SELECT item_id, item_signature, name, sample_count, min_unit_price, last_seen_at
            FROM ranked
            WHERE best_rank = 1
            ORDER BY match_rank ASC, min_unit_price ASC, last_seen_at DESC
            LIMIT ?
            """.trimIndent()

        val hits = mutableListOf<ItemSearchHit>()
        val conn = ensureConnection()
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, fromTimestamp)
            ps.setString(2, normalizedTerm)
            ps.setString(3, contains)
            ps.setString(4, contains)
            ps.setString(5, normalizedTerm)
            ps.setString(6, normalizedTerm)
            ps.setString(7, prefix)
            ps.setString(8, contains)
            ps.setString(9, contains)
            ps.setInt(10, limit.coerceIn(1, 500))
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    hits.add(
                        ItemSearchHit(
                            itemId = rs.getString("item_id"),
                            itemSignature = rs.getString("item_signature"),
                            displayName = rs.getString("name"),
                            sampleCount = rs.getInt("sample_count"),
                            minUnitPrice = rs.getDouble("min_unit_price"),
                            lastSeenAt = rs.getLong("last_seen_at")
                        )
                    )
                }
            }
        }
        return hits
    }

    @Synchronized
    override fun getItemStats(itemId: String, itemSignature: String, lookbackDays: Int, stackSize: Int): ItemPriceStats? {
        val fromTimestamp = lookbackLowerBoundMs(lookbackDays)
        val multiplier = stackSize.coerceAtLeast(1).toDouble()

        val sql =
            """
            WITH base AS (
                SELECT unit_price, captured_at
                FROM ah_listings
                WHERE item_id = ?
                  AND item_signature = ?
                  AND unit_price IS NOT NULL
                  AND captured_at >= ?
            ), ordered AS (
                SELECT
                    unit_price,
                    row_number() OVER (ORDER BY unit_price) AS rn,
                    count(*) OVER () AS cnt
                FROM base
            ), median AS (
                SELECT avg(unit_price) AS median_unit_price
                FROM ordered
                WHERE rn IN ((cnt + 1) / 2, (cnt + 2) / 2)
            )
            SELECT
                (SELECT count(*) FROM base) AS sample_count,
                (SELECT min(unit_price) FROM base) AS min_unit_price,
                (SELECT avg(unit_price) FROM base) AS avg_unit_price,
                (SELECT median_unit_price FROM median) AS median_unit_price,
                (SELECT max(captured_at) FROM base) AS last_seen_at
            """.trimIndent()

        val conn = ensureConnection()
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, itemId)
            ps.setString(2, itemSignature)
            ps.setLong(3, fromTimestamp)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null

                val sampleCount = rs.getInt("sample_count")
                if (sampleCount <= 0) return null

                val minUnit = rs.getDouble("min_unit_price")
                val avgUnit = rs.getDouble("avg_unit_price")
                val medianUnit = rs.getDouble("median_unit_price")
                val lastSeenAt = rs.getLong("last_seen_at")

                return ItemPriceStats(
                    itemId = itemId,
                    itemSignature = itemSignature,
                    sampleCount = sampleCount,
                    minUnitPrice = minUnit,
                    avgUnitPrice = avgUnit,
                    medianUnitPrice = medianUnit,
                    stackSize = multiplier.toInt(),
                    minStackPrice = minUnit * multiplier,
                    avgStackPrice = avgUnit * multiplier,
                    medianStackPrice = medianUnit * multiplier,
                    lastSeenAt = lastSeenAt
                )
            }
        }
    }

    @Synchronized
    override fun getLowestListings(itemId: String, itemSignature: String, lookbackDays: Int, limit: Int): List<ListingPreview> {
        val fromTimestamp = lookbackLowerBoundMs(lookbackDays)
        val sql =
            """
            SELECT item_id, item_signature, name, seller, amount, total_price, unit_price, captured_at
            FROM ah_listings
            WHERE item_id = ?
              AND item_signature = ?
              AND unit_price IS NOT NULL
              AND captured_at >= ?
            ORDER BY unit_price ASC, captured_at DESC
            LIMIT ?
            """.trimIndent()

        val rows = mutableListOf<ListingPreview>()
        val conn = ensureConnection()
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, itemId)
            ps.setString(2, itemSignature)
            ps.setLong(3, fromTimestamp)
            ps.setInt(4, limit.coerceIn(1, 200))
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    rows.add(mapListingPreview(rs))
                }
            }
        }
        return rows
    }

    @Synchronized
    override fun findBestDeals(
        lookbackDays: Int,
        minDiscountPct: Double,
        minSamples: Int,
        watchlistOnly: Boolean,
        limit: Int
    ): List<DealCandidate> {
        val baselineFrom = lookbackLowerBoundMs(lookbackDays)
        val current24hFrom = System.currentTimeMillis() - (24L * 60L * 60L * 1000L)

        val sql =
            """
            WITH baseline AS (
                SELECT
                    item_id,
                    item_signature,
                    min(name) AS display_name,
                    count(*) AS sample_count,
                    avg(unit_price) AS avg_price,
                    max(captured_at) AS last_seen_at
                FROM ah_listings
                WHERE unit_price IS NOT NULL AND captured_at >= ?
                GROUP BY item_id, item_signature
            ),
            ordered AS (
                SELECT
                    item_id,
                    item_signature,
                    unit_price,
                    row_number() OVER (PARTITION BY item_id, item_signature ORDER BY unit_price) AS rn,
                    count(*) OVER (PARTITION BY item_id, item_signature) AS cnt
                FROM ah_listings
                WHERE unit_price IS NOT NULL AND captured_at >= ?
            ),
            median AS (
                SELECT
                    item_id,
                    item_signature,
                    avg(unit_price) AS median_price
                FROM ordered
                WHERE rn IN ((cnt + 1) / 2, (cnt + 2) / 2)
                GROUP BY item_id, item_signature
            ),
            current24 AS (
                SELECT
                    item_id,
                    item_signature,
                    min(unit_price) AS current_min
                FROM ah_listings
                WHERE unit_price IS NOT NULL AND captured_at >= ?
                GROUP BY item_id, item_signature
            )
            SELECT
                b.item_id,
                b.item_signature,
                b.display_name,
                c.current_min,
                m.median_price,
                b.avg_price,
                CASE
                    WHEN m.median_price > 0 THEN max(0.0, ((m.median_price - c.current_min) / m.median_price) * 100.0)
                    ELSE 0.0
                END AS discount_median,
                CASE
                    WHEN b.avg_price > 0 THEN max(0.0, ((b.avg_price - c.current_min) / b.avg_price) * 100.0)
                    ELSE 0.0
                END AS discount_avg,
                b.sample_count,
                b.last_seen_at
            FROM baseline b
            JOIN median m
              ON m.item_id = b.item_id AND m.item_signature = b.item_signature
            JOIN current24 c
              ON c.item_id = b.item_id AND c.item_signature = b.item_signature
            LEFT JOIN ah_watchlist w
              ON w.item_id = b.item_id AND w.item_signature = b.item_signature
            WHERE b.sample_count >= ?
              AND m.median_price > 0
              AND (? = 0 OR w.item_id IS NOT NULL)
              AND (
                CASE
                    WHEN m.median_price > 0 THEN max(0.0, ((m.median_price - c.current_min) / m.median_price) * 100.0)
                    ELSE 0.0
                END
              ) >= ?
            ORDER BY discount_median DESC, b.last_seen_at DESC
            LIMIT ?
            """.trimIndent()

        val rows = mutableListOf<DealCandidate>()
        val conn = ensureConnection()
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, baselineFrom)
            ps.setLong(2, baselineFrom)
            ps.setLong(3, current24hFrom)
            ps.setInt(4, minSamples.coerceAtLeast(1))
            ps.setInt(5, if (watchlistOnly) 1 else 0)
            ps.setDouble(6, minDiscountPct.coerceAtLeast(0.0))
            ps.setInt(7, limit.coerceIn(1, 500))
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    rows.add(
                        DealCandidate(
                            itemId = rs.getString("item_id"),
                            itemSignature = rs.getString("item_signature"),
                            displayName = rs.getString("display_name"),
                            currentMinUnitPrice = rs.getDouble("current_min"),
                            median30d = rs.getDouble("median_price"),
                            avg30d = rs.getDouble("avg_price"),
                            discountVsMedianPct = rs.getDouble("discount_median"),
                            discountVsAvgPct = rs.getDouble("discount_avg"),
                            sampleCount30d = rs.getInt("sample_count"),
                            lastSeenAt = rs.getLong("last_seen_at")
                        )
                    )
                }
            }
        }
        return rows
    }

    @Synchronized
    override fun setWatchlist(itemId: String, itemSignature: String, displayName: String, enabled: Boolean) {
        requireWritable("setWatchlist")
        val conn = ensureConnection()
        if (enabled) {
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO ah_watchlist(item_id, item_signature, display_name, created_at)
                VALUES (?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, itemId)
                ps.setString(2, itemSignature)
                ps.setString(3, displayName)
                ps.setLong(4, System.currentTimeMillis())
                ps.executeUpdate()
            }
        } else {
            conn.prepareStatement("DELETE FROM ah_watchlist WHERE item_id = ? AND item_signature = ?").use { ps ->
                ps.setString(1, itemId)
                ps.setString(2, itemSignature)
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    override fun listWatchlist(): List<WatchlistItem> {
        val rows = mutableListOf<WatchlistItem>()
        ensureConnection().createStatement().use { stmt ->
            stmt.executeQuery(
                """
                SELECT item_id, item_signature, display_name, created_at
                FROM ah_watchlist
                ORDER BY created_at DESC
                """.trimIndent()
            ).use { rs ->
                while (rs.next()) {
                    rows.add(
                        WatchlistItem(
                            itemId = rs.getString("item_id"),
                            itemSignature = rs.getString("item_signature"),
                            displayName = rs.getString("display_name"),
                            createdAt = rs.getLong("created_at")
                        )
                    )
                }
            }
        }
        return rows
    }

    @Synchronized
    override fun getTrendStats(itemId: String, itemSignature: String): ItemTrendStats {
        val now = System.currentTimeMillis()
        val windows = listOf(
            "24h" to (now - 24L * 60L * 60L * 1000L),
            "7d" to (now - 7L * 24L * 60L * 60L * 1000L),
            "30d" to (now - 30L * 24L * 60L * 60L * 1000L)
        )

        val stats = windows.map { (label, fromTs) ->
            queryWindowStat(itemId, itemSignature, label, fromTs)
        }

        return ItemTrendStats(itemId = itemId, itemSignature = itemSignature, windows = stats)
    }

    @Synchronized
    override fun getTrendSeries(itemId: String, itemSignature: String, windowHours: Int, bucketMinutes: Int): ItemTrendSeries {
        val safeWindowHours = windowHours.coerceAtLeast(1)
        val safeBucketMinutes = bucketMinutes.coerceAtLeast(1)
        val windowMs = safeWindowHours.toLong() * 60L * 60L * 1000L
        val bucketMs = safeBucketMinutes.toLong() * 60L * 1000L
        val fromTimestamp = System.currentTimeMillis() - windowMs

        val sql =
            """
            WITH base AS (
                SELECT
                    unit_price,
                    ((captured_at / ?) * ?) AS bucket_start
                FROM ah_listings
                WHERE item_id = ?
                  AND item_signature = ?
                  AND unit_price IS NOT NULL
                  AND captured_at >= ?
            ),
            ordered AS (
                SELECT
                    bucket_start,
                    unit_price,
                    row_number() OVER (PARTITION BY bucket_start ORDER BY unit_price) AS rn,
                    count(*) OVER (PARTITION BY bucket_start) AS cnt
                FROM base
            ),
            medians AS (
                SELECT
                    bucket_start,
                    avg(unit_price) AS median_unit_price
                FROM ordered
                WHERE rn IN ((cnt + 1) / 2, (cnt + 2) / 2)
                GROUP BY bucket_start
            )
            SELECT
                b.bucket_start,
                min(b.unit_price) AS min_unit_price,
                m.median_unit_price,
                count(*) AS sample_count
            FROM base b
            JOIN medians m ON m.bucket_start = b.bucket_start
            GROUP BY b.bucket_start, m.median_unit_price
            ORDER BY b.bucket_start ASC
            """.trimIndent()

        val points = mutableListOf<TrendSeriesPoint>()
        val conn = ensureConnection()
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, bucketMs)
            ps.setLong(2, bucketMs)
            ps.setString(3, itemId)
            ps.setString(4, itemSignature)
            ps.setLong(5, fromTimestamp)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    points.add(
                        TrendSeriesPoint(
                            bucketStartAt = rs.getLong("bucket_start"),
                            minUnitPrice = rs.getDouble("min_unit_price").takeIf { !rs.wasNull() },
                            medianUnitPrice = rs.getDouble("median_unit_price").takeIf { !rs.wasNull() },
                            sampleCount = rs.getInt("sample_count")
                        )
                    )
                }
            }
        }

        return ItemTrendSeries(
            windowLabel = formatWindowLabel(safeWindowHours),
            bucketMinutes = safeBucketMinutes,
            points = points
        )
    }

    @Synchronized
    override fun getPercentileStats(itemId: String, itemSignature: String, lookbackDays: Int): ItemPercentileStats? {
        val fromTimestamp = lookbackLowerBoundMs(lookbackDays)
        val sql =
            """
            WITH base AS (
                SELECT unit_price, captured_at
                FROM ah_listings
                WHERE item_id = ?
                  AND item_signature = ?
                  AND unit_price IS NOT NULL
                  AND captured_at >= ?
            ),
            ordered AS (
                SELECT
                    unit_price,
                    captured_at,
                    row_number() OVER (ORDER BY unit_price) AS rn,
                    count(*) OVER () AS cnt
                FROM base
            )
            SELECT
                (SELECT count(*) FROM base) AS sample_count,
                (SELECT max(captured_at) FROM base) AS last_seen_at,
                (SELECT unit_price FROM ordered WHERE rn = ((cnt + 9) / 10) LIMIT 1) AS p10,
                (SELECT avg(unit_price) FROM ordered WHERE rn IN ((cnt + 1) / 2, (cnt + 2) / 2)) AS p50,
                (SELECT unit_price FROM ordered WHERE rn = ((cnt * 9 + 9) / 10) LIMIT 1) AS p90
            """.trimIndent()

        val conn = ensureConnection()
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, itemId)
            ps.setString(2, itemSignature)
            ps.setLong(3, fromTimestamp)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null

                val sampleCount = rs.getInt("sample_count")
                if (sampleCount <= 0) return null

                val p10 = rs.getDouble("p10")
                val p50 = rs.getDouble("p50")
                val p90 = rs.getDouble("p90")
                val spreadAbs = p90 - p10
                val spreadRatio = if (p10 > 0.0) p90 / p10 else 0.0

                return ItemPercentileStats(
                    itemId = itemId,
                    itemSignature = itemSignature,
                    lookbackDays = lookbackDays,
                    sampleCount = sampleCount,
                    p10 = p10,
                    p50 = p50,
                    p90 = p90,
                    spreadAbs = spreadAbs,
                    spreadRatio = spreadRatio,
                    lastSeenAt = rs.getLong("last_seen_at")
                )
            }
        }
    }

    @Synchronized
    override fun getSellerStats(itemId: String, itemSignature: String, lookbackDays: Int, limit: Int): List<SellerStatsRow> {
        val fromTimestamp = lookbackLowerBoundMs(lookbackDays)
        val sql =
            """
            SELECT
                coalesce(nullif(trim(seller), ''), '?') AS seller_name,
                count(*) AS listing_count,
                min(unit_price) AS min_unit_price,
                avg(unit_price) AS avg_unit_price,
                max(captured_at) AS last_seen_at
            FROM ah_listings
            WHERE item_id = ?
              AND item_signature = ?
              AND unit_price IS NOT NULL
              AND captured_at >= ?
            GROUP BY seller_name
            ORDER BY listing_count DESC, min_unit_price ASC, last_seen_at DESC
            LIMIT ?
            """.trimIndent()

        val rows = mutableListOf<SellerStatsRow>()
        val conn = ensureConnection()
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, itemId)
            ps.setString(2, itemSignature)
            ps.setLong(3, fromTimestamp)
            ps.setInt(4, limit.coerceIn(1, 200))
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    rows.add(
                        SellerStatsRow(
                            seller = rs.getString("seller_name"),
                            listingCount = rs.getInt("listing_count"),
                            minUnitPrice = rs.getDouble("min_unit_price"),
                            avgUnitPrice = rs.getDouble("avg_unit_price"),
                            lastSeenAt = rs.getLong("last_seen_at")
                        )
                    )
                }
            }
        }
        return rows
    }

    @Synchronized
    override fun rebuildIndexes() {
        if (!writesEnabled) {
            logger.info("[DonutsAuctions][db] reindex skipped: repository is read-only")
            return
        }
        ensureConnection().createStatement().use { stmt ->
            stmt.execute("REINDEX;")
        }
    }

    @Synchronized
    override fun vacuum() {
        if (!writesEnabled) {
            logger.info("[DonutsAuctions][db] vacuum skipped: repository is read-only")
            return
        }
        ensureConnection().createStatement().use { stmt ->
            stmt.execute("VACUUM;")
        }
    }

    @Synchronized
    override fun exportItemHistoryCsv(itemId: String, itemSignature: String, lookbackDays: Int, outputDir: Path): Path? {
        val rows = getLowestListings(itemId, itemSignature, lookbackDays, limit = 5000)
        if (rows.isEmpty()) {
            return null
        }

        Files.createDirectories(outputDir)
        val file = outputDir.resolve("history-${sanitizeFilename(itemId)}-${itemSignature.take(8)}.csv")

        val header = "captured_at,item_id,item_signature,name,seller,amount,total_price,unit_price\n"
        val body = buildString {
            rows.forEach { row ->
                append(row.capturedAt)
                append(',')
                append(csvCell(row.itemId))
                append(',')
                append(csvCell(row.itemSignature))
                append(',')
                append(csvCell(row.name))
                append(',')
                append(csvCell(row.seller ?: ""))
                append(',')
                append(row.amount)
                append(',')
                append(row.totalPrice ?: "")
                append(',')
                append(row.unitPrice)
                append('\n')
            }
        }

        Files.writeString(file, header + body)
        return file
    }

    @Synchronized
    override fun exportItemSummaryJson(itemId: String, itemSignature: String, lookbackDays: Int, outputDir: Path): Path? {
        val stats = getItemStats(itemId, itemSignature, lookbackDays, stackSize = 1) ?: return null
        val trend = getTrendStats(itemId, itemSignature)
        val trend25h = getTrendSeries(itemId, itemSignature, windowHours = 25, bucketMinutes = 60)
        val trend7d = getTrendSeries(itemId, itemSignature, windowHours = 7 * 24, bucketMinutes = 360)
        val trend30d = getTrendSeries(itemId, itemSignature, windowHours = 30 * 24, bucketMinutes = 1440)
        val percentiles = getPercentileStats(itemId, itemSignature, lookbackDays)
        val sellers = getSellerStats(itemId, itemSignature, lookbackDays, limit = 20)
        val listings = getLowestListings(itemId, itemSignature, lookbackDays, limit = 20)

        Files.createDirectories(outputDir)
        val file = outputDir.resolve("summary-${sanitizeFilename(itemId)}-${itemSignature.take(8)}.json")

        val json = buildString {
            append("{\n")
            append("  \"itemId\": \"").append(jsonEsc(itemId)).append("\",\n")
            append("  \"itemSignature\": \"").append(jsonEsc(itemSignature)).append("\",\n")
            append("  \"lookbackDays\": ").append(lookbackDays).append(",\n")
            append("  \"stats\": {\n")
            append("    \"sampleCount\": ").append(stats.sampleCount).append(",\n")
            append("    \"minUnitPrice\": ").append(stats.minUnitPrice).append(",\n")
            append("    \"avgUnitPrice\": ").append(stats.avgUnitPrice).append(",\n")
            append("    \"medianUnitPrice\": ").append(stats.medianUnitPrice).append(",\n")
            append("    \"lastSeenAt\": ").append(stats.lastSeenAt).append("\n")
            append("  },\n")

            append("  \"trend\": [\n")
            trend.windows.forEachIndexed { index, window ->
                append("    {\"window\":\"").append(jsonEsc(window.windowLabel)).append("\",")
                append("\"sampleCount\":").append(window.sampleCount).append(',')
                append("\"minUnitPrice\":").append(window.minUnitPrice ?: "null").append(',')
                append("\"medianUnitPrice\":").append(window.medianUnitPrice ?: "null").append(',')
                append("\"lastSeenAt\":").append(window.lastSeenAt ?: "null").append("}")
                append(if (index < trend.windows.lastIndex) ",\n" else "\n")
            }
            append("  ],\n")

            append("  \"trendSeries\": {\n")
            append("    \"25h\": ").append(seriesJson(trend25h.points)).append(",\n")
            append("    \"7d\": ").append(seriesJson(trend7d.points)).append(",\n")
            append("    \"30d\": ").append(seriesJson(trend30d.points)).append("\n")
            append("  },\n")

            append("  \"percentiles\": ")
            if (percentiles == null) {
                append("null,\n")
            } else {
                append("{\"sampleCount\":").append(percentiles.sampleCount)
                append(",\"p10\":").append(percentiles.p10)
                append(",\"p50\":").append(percentiles.p50)
                append(",\"p90\":").append(percentiles.p90)
                append(",\"spreadAbs\":").append(percentiles.spreadAbs)
                append(",\"spreadRatio\":").append(percentiles.spreadRatio)
                append("},\n")
            }

            append("  \"sellers\": [\n")
            sellers.forEachIndexed { index, seller ->
                append("    {\"seller\":\"").append(jsonEsc(seller.seller)).append("\",")
                append("\"listingCount\":").append(seller.listingCount).append(',')
                append("\"minUnitPrice\":").append(seller.minUnitPrice).append(',')
                append("\"avgUnitPrice\":").append(seller.avgUnitPrice).append(',')
                append("\"lastSeenAt\":").append(seller.lastSeenAt).append("}")
                append(if (index < sellers.lastIndex) ",\n" else "\n")
            }
            append("  ],\n")

            append("  \"cheapestListings\": [\n")
            listings.forEachIndexed { index, row ->
                append("    {\"capturedAt\":").append(row.capturedAt).append(',')
                append("\"seller\":\"").append(jsonEsc(row.seller ?: "")).append("\",")
                append("\"amount\":").append(row.amount).append(',')
                append("\"totalPrice\":").append(row.totalPrice ?: "null").append(',')
                append("\"unitPrice\":").append(row.unitPrice).append("}")
                append(if (index < listings.lastIndex) ",\n" else "\n")
            }
            append("  ]\n")
            append("}\n")
        }

        Files.writeString(file, json)
        return file
    }

    @Synchronized
    override fun enforceMaxSize(maxSizeMb: Int) {
        if (!writesEnabled) {
            return
        }
        if (maxSizeMb <= 0) return
        if (!Files.exists(dbPath)) return

        val maxBytes = maxSizeMb.toLong() * 1024L * 1024L
        if (dbPath.fileSize() <= maxBytes) {
            return
        }

        ensureConnection().createStatement().use { stmt ->
            stmt.execute(
                """
                DELETE FROM ah_snapshots
                WHERE id IN (
                    SELECT id FROM ah_snapshots ORDER BY captured_at ASC LIMIT 100
                );
                """.trimIndent()
            )
            stmt.execute("VACUUM;")
        }
    }

    @Synchronized
    override fun close() {
        connection?.close()
        connection = null
    }

    @Synchronized
    private fun ensureConnection(): Connection {
        connection?.let { return it }

        try {
            Class.forName("org.sqlite.JDBC")
        } catch (ex: ClassNotFoundException) {
            throw IllegalStateException(
                "SQLite JDBC driver not available on classpath. Ensure sqlite-jdbc is bundled with the mod.",
                ex
            )
        }

        Files.createDirectories(dbPath.parent)
        val conn = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        configureConnection(conn)
        connection = conn
        return conn
    }

    private fun mapListing(rs: ResultSet): AuctionListingRecord {
        return AuctionListingRecord(
            capturedAt = rs.getLong("captured_at"),
            query = rs.getString("query"),
            page = null,
            slot = -1,
            itemId = rs.getString("item_id"),
            itemSignature = rs.getString("item_signature"),
            name = rs.getString("name"),
            amount = rs.getInt("amount"),
            totalPrice = rs.getLong("total_price").takeIf { !rs.wasNull() },
            unitPrice = rs.getDouble("unit_price").takeIf { !rs.wasNull() },
            seller = rs.getString("seller"),
            rawLore = rs.getString("raw_lore"),
            sortMode = null,
            filterMode = null,
            snapshotHash = rs.getString("snapshot_hash")
        )
    }

    private fun mapListingPreview(rs: ResultSet): ListingPreview {
        return ListingPreview(
            itemId = rs.getString("item_id"),
            itemSignature = rs.getString("item_signature"),
            name = rs.getString("name"),
            seller = rs.getString("seller"),
            amount = rs.getInt("amount"),
            totalPrice = rs.getLong("total_price").takeIf { !rs.wasNull() },
            unitPrice = rs.getDouble("unit_price"),
            capturedAt = rs.getLong("captured_at")
        )
    }

    private fun ensureListingsTableSchema(conn: Connection) {
        val existingColumns = getTableColumns(conn, "ah_listings")
        if (existingColumns.isEmpty()) {
            createListingsTable(conn)
            return
        }

        if (existingColumns == expectedListingColumns) {
            return
        }

        conn.createStatement().use { stmt ->
            stmt.execute("ALTER TABLE ah_listings RENAME TO ah_listings_old;")
        }
        createListingsTable(conn)

        val commonColumns = expectedListingColumns.filter { it in existingColumns }
        if (commonColumns.isNotEmpty()) {
            val columnCsv = commonColumns.joinToString(", ")
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO ah_listings($columnCsv)
                    SELECT $columnCsv
                    FROM ah_listings_old;
                    """.trimIndent()
                )
            }
        }

        conn.createStatement().use { stmt ->
            stmt.execute("DROP TABLE ah_listings_old;")
        }
    }

    private fun createListingsTable(conn: Connection) {
        conn.createStatement().use { stmt ->
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
                    snapshot_hash TEXT NOT NULL,
                    FOREIGN KEY(snapshot_id) REFERENCES ah_snapshots(id) ON DELETE CASCADE
                );
                """.trimIndent()
            )
        }
    }

    private fun configureConnection(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA foreign_keys=ON;")
            stmt.execute("PRAGMA journal_mode=WAL;")
            stmt.execute("PRAGMA synchronous=NORMAL;")
        }
    }

    private fun ensureBaseTables(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS ah_snapshots (
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
                CREATE TABLE IF NOT EXISTS ah_watchlist (
                    item_id TEXT NOT NULL,
                    item_signature TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    PRIMARY KEY(item_id, item_signature)
                );
                """.trimIndent()
            )
        }
    }

    private fun ensureReadOnlyCompatibility(conn: Connection) {
        val listingColumns = getTableColumns(conn, "ah_listings")
        if (listingColumns.isEmpty()) {
            createListingsTable(conn)
            return
        }
        if (!listingColumns.contains("item_signature")) {
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE ah_listings ADD COLUMN item_signature TEXT NOT NULL DEFAULT '';")
            }
        }
    }

    private fun createCoreIndexes(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_listings_query_item_time ON ah_listings(query, item_id, captured_at);")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_listings_item_unit_price ON ah_listings(item_id, unit_price);")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_listings_item_signature_time ON ah_listings(item_id, item_signature, captured_at);")
            stmt.execute(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS uq_listings_snapshot_row
                ON ah_listings(
                    snapshot_id,
                    item_id,
                    item_signature,
                    coalesce(total_price, -1),
                    amount,
                    coalesce(seller, '')
                );
                """.trimIndent()
            )
        }
    }

    private fun countDuplicateGroups(conn: Connection): Int {
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                """
                SELECT count(*) AS duplicate_groups
                FROM (
                    SELECT
                        snapshot_id,
                        item_id,
                        item_signature,
                        coalesce(total_price, -1) AS total_price_key,
                        amount,
                        coalesce(seller, '') AS seller_key,
                        count(*) AS row_count
                    FROM ah_listings
                    GROUP BY snapshot_id, item_id, item_signature, total_price_key, amount, seller_key
                    HAVING row_count > 1
                ) g
                """.trimIndent()
            ).use { rs ->
                return if (rs.next()) rs.getInt("duplicate_groups") else 0
            }
        }
    }

    private fun countLegacyRows(conn: Connection): Int {
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT count(*) AS legacy_rows FROM ah_listings WHERE item_signature = '';").use { rs ->
                return if (rs.next()) rs.getInt("legacy_rows") else 0
            }
        }
    }

    private fun countShulkerRows(conn: Connection): Int {
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT count(*) AS shulker_rows FROM ah_listings WHERE lower(item_id) LIKE '%shulker_box%';").use { rs ->
                return if (rs.next()) rs.getInt("shulker_rows") else 0
            }
        }
    }

    private fun removeDuplicateRows(conn: Connection): Int {
        conn.createStatement().use { stmt ->
            return stmt.executeUpdate(
                """
                WITH ranked AS (
                    SELECT
                        id,
                        row_number() OVER (
                            PARTITION BY
                                snapshot_id,
                                item_id,
                                item_signature,
                                coalesce(total_price, -1),
                                amount,
                                coalesce(seller, '')
                            ORDER BY captured_at DESC, id DESC
                        ) AS rn
                    FROM ah_listings
                )
                DELETE FROM ah_listings
                WHERE id IN (SELECT id FROM ranked WHERE rn > 1);
                """.trimIndent()
            )
        }
    }

    private fun deleteLegacyRows(conn: Connection): Int {
        conn.createStatement().use { stmt ->
            return stmt.executeUpdate("DELETE FROM ah_listings WHERE item_signature = '';")
        }
    }

    private fun deleteShulkerRows(conn: Connection): Int {
        conn.createStatement().use { stmt ->
            return stmt.executeUpdate("DELETE FROM ah_listings WHERE lower(item_id) LIKE '%shulker_box%';")
        }
    }

    private fun pruneOrphanSnapshots(conn: Connection): Int {
        conn.createStatement().use { stmt ->
            return stmt.executeUpdate(
                """
                DELETE FROM ah_snapshots
                WHERE id NOT IN (SELECT DISTINCT snapshot_id FROM ah_listings);
                """.trimIndent()
            )
        }
    }

    private fun backupDatabase(): Path? {
        if (!Files.exists(dbPath) || Files.size(dbPath) == 0L) {
            return null
        }

        val backupDir = dbPath.parent.resolve("backups")
        Files.createDirectories(backupDir)
        val fileName = "auctions-pre-v3_1-${System.currentTimeMillis()}.db"
        val destination = backupDir.resolve(fileName)
        Files.copy(dbPath, destination, StandardCopyOption.REPLACE_EXISTING)
        val walSource = Path.of("${dbPath.toAbsolutePath()}-wal")
        if (Files.exists(walSource)) {
            Files.copy(walSource, Path.of("${destination.toAbsolutePath()}-wal"), StandardCopyOption.REPLACE_EXISTING)
        }
        val shmSource = Path.of("${dbPath.toAbsolutePath()}-shm")
        if (Files.exists(shmSource)) {
            Files.copy(shmSource, Path.of("${destination.toAbsolutePath()}-shm"), StandardCopyOption.REPLACE_EXISTING)
        }
        logger.info("[DonutsAuctions][db] backup created at {}", destination)
        return destination
    }

    private fun countRows(): Pair<Int, Int> {
        val conn = ensureConnection()
        val snapshots = conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT count(*) AS c FROM ah_snapshots;").use { rs ->
                if (rs.next()) rs.getInt("c") else 0
            }
        }
        val listings = conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT count(*) AS c FROM ah_listings;").use { rs ->
                if (rs.next()) rs.getInt("c") else 0
            }
        }
        return snapshots to listings
    }

    private fun requireWritable(operation: String) {
        if (writesEnabled) {
            return
        }
        health = health.copy(message = "Repository is read-only; '$operation' is disabled")
        throw IllegalStateException("Repository is read-only; write operation '$operation' is disabled")
    }

    private fun queryWindowStat(itemId: String, itemSignature: String, label: String, fromTimestamp: Long): TrendWindowStat {
        val sql =
            """
            WITH base AS (
                SELECT unit_price, captured_at
                FROM ah_listings
                WHERE item_id = ?
                  AND item_signature = ?
                  AND unit_price IS NOT NULL
                  AND captured_at >= ?
            ),
            ordered AS (
                SELECT
                    unit_price,
                    row_number() OVER (ORDER BY unit_price) AS rn,
                    count(*) OVER () AS cnt
                FROM base
            )
            SELECT
                (SELECT count(*) FROM base) AS sample_count,
                (SELECT min(unit_price) FROM base) AS min_unit_price,
                (SELECT avg(unit_price) FROM ordered WHERE rn IN ((cnt + 1) / 2, (cnt + 2) / 2)) AS median_unit_price,
                (SELECT max(captured_at) FROM base) AS last_seen_at
            """.trimIndent()

        val conn = ensureConnection()
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, itemId)
            ps.setString(2, itemSignature)
            ps.setLong(3, fromTimestamp)
            ps.executeQuery().use { rs ->
                rs.next()
                val sampleCount = rs.getInt("sample_count")
                val min = rs.getDouble("min_unit_price").takeIf { !rs.wasNull() }
                val median = rs.getDouble("median_unit_price").takeIf { !rs.wasNull() }
                val last = rs.getLong("last_seen_at").takeIf { !rs.wasNull() }
                return TrendWindowStat(
                    windowLabel = label,
                    minUnitPrice = min,
                    medianUnitPrice = median,
                    sampleCount = sampleCount,
                    lastSeenAt = last
                )
            }
        }
    }

    private fun getTableColumns(conn: Connection, tableName: String): List<String> {
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info($tableName);").use { rs ->
                val columns = mutableListOf<String>()
                while (rs.next()) {
                    columns.add(rs.getString("name"))
                }
                return columns
            }
        }
    }

    private fun lookbackLowerBoundMs(lookbackDays: Int): Long {
        if (lookbackDays <= 0) {
            return 0L
        }
        return System.currentTimeMillis() - (lookbackDays.toLong() * 24L * 60L * 60L * 1000L)
    }

    private fun formatWindowLabel(windowHours: Int): String {
        return when (windowHours) {
            25 -> "25h"
            24 -> "24h"
            7 * 24 -> "7d"
            30 * 24 -> "30d"
            else -> "${windowHours}h"
        }
    }

    private fun csvCell(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    private fun jsonEsc(value: String): String {
        return buildString {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
    }

    private fun seriesJson(points: List<TrendSeriesPoint>): String {
        return buildString {
            append("[")
            points.forEachIndexed { index, point ->
                append("{\"bucketStartAt\":").append(point.bucketStartAt).append(',')
                append("\"minUnitPrice\":").append(point.minUnitPrice ?: "null").append(',')
                append("\"medianUnitPrice\":").append(point.medianUnitPrice ?: "null").append(',')
                append("\"sampleCount\":").append(point.sampleCount).append("}")
                if (index < points.lastIndex) {
                    append(',')
                }
            }
            append("]")
        }
    }

    private fun sanitizeFilename(raw: String): String {
        return raw.replace(':', '_').replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }
}
