package de.lukas.donutsauctions.model

data class SellerStatsRow(
    val seller: String,
    val listingCount: Int,
    val minUnitPrice: Double,
    val avgUnitPrice: Double,
    val lastSeenAt: Long
)
