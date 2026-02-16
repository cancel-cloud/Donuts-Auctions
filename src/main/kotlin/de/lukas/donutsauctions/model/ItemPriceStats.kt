package de.lukas.donutsauctions.model

data class ItemPriceStats(
    val itemId: String,
    val itemSignature: String,
    val sampleCount: Int,
    val minUnitPrice: Double,
    val avgUnitPrice: Double,
    val medianUnitPrice: Double,
    val stackSize: Int,
    val minStackPrice: Double,
    val avgStackPrice: Double,
    val medianStackPrice: Double,
    val lastSeenAt: Long
)
