package de.lukas.donutsauctions.model

data class TrendWindowStat(
    val windowLabel: String,
    val minUnitPrice: Double?,
    val medianUnitPrice: Double?,
    val sampleCount: Int,
    val lastSeenAt: Long?
)

data class TrendSeriesPoint(
    val bucketStartAt: Long,
    val minUnitPrice: Double?,
    val medianUnitPrice: Double?,
    val sampleCount: Int
)

data class ItemTrendSeries(
    val windowLabel: String,
    val bucketMinutes: Int,
    val points: List<TrendSeriesPoint>
)

data class ItemTrendStats(
    val itemId: String,
    val itemSignature: String,
    val windows: List<TrendWindowStat>
)
