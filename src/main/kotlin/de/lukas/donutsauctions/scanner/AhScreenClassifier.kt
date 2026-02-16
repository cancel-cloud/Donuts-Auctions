package de.lukas.donutsauctions.scanner

fun interface AhScreenClassifier {
    fun isAuctionScreen(title: String, controlItems: Set<String>): Boolean
}
