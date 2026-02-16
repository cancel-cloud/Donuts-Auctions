package de.lukas.donutsauctions.model

data class WatchlistItem(
    val itemId: String,
    val itemSignature: String,
    val displayName: String,
    val createdAt: Long
)
