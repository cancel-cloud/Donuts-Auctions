package de.lukas.donutsauctions.model

data class ItemPercentileStats(
    val itemId: String,
    val itemSignature: String,
    val lookbackDays: Int,
    val sampleCount: Int,
    val p10: Double,
    val p50: Double,
    val p90: Double,
    val spreadAbs: Double,
    val spreadRatio: Double,
    val lastSeenAt: Long
)
