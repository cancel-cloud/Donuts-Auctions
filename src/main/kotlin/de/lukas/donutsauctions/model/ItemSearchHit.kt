package de.lukas.donutsauctions.model

data class ItemSearchHit(
    val itemId: String,
    val itemSignature: String,
    val displayName: String,
    val sampleCount: Int,
    val minUnitPrice: Double,
    val lastSeenAt: Long
)
