package de.lukas.donutsauctions.model

data class DealCandidate(
    val itemId: String,
    val itemSignature: String,
    val displayName: String,
    val currentMinUnitPrice: Double,
    val median30d: Double,
    val avg30d: Double,
    val discountVsMedianPct: Double,
    val discountVsAvgPct: Double,
    val sampleCount30d: Int,
    val lastSeenAt: Long
)
