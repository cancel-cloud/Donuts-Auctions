package de.lukas.donutsauctions.model

enum class TrackedMenuType(
    private val titleMarkers: List<String>
) {
    AUCTION(listOf("AUCTION", "PAGE"));

    fun matchesTitle(title: String): Boolean {
        val normalized = title.uppercase()
        return titleMarkers.all { marker -> normalized.contains(marker) }
    }
}
