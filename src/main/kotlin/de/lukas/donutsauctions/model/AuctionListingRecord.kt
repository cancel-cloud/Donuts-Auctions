package de.lukas.donutsauctions.model

data class AuctionListingRecord(
    val capturedAt: Long,
    val query: String,
    val page: Int?,
    val slot: Int,
    val itemId: String,
    val itemSignature: String,
    val name: String,
    val amount: Int,
    val totalPrice: Long?,
    val unitPrice: Double?,
    val seller: String?,
    val rawLore: String,
    val sortMode: String?,
    val filterMode: String?,
    val snapshotHash: String
)
