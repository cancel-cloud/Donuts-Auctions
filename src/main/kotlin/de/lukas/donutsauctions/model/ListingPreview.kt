package de.lukas.donutsauctions.model

data class ListingPreview(
    val itemId: String,
    val itemSignature: String,
    val name: String,
    val seller: String?,
    val amount: Int,
    val totalPrice: Long?,
    val unitPrice: Double,
    val capturedAt: Long
)
