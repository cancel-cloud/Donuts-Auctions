package de.lukas.donutsauctions.scanner

class DefaultAhScreenClassifier : AhScreenClassifier {

    override fun isAuctionScreen(title: String, controlItems: Set<String>): Boolean {
        val normalizedTitle = title.uppercase()
        if (!normalizedTitle.contains("AUCTION") || !normalizedTitle.contains("PAGE")) {
            return false
        }

        val normalizedControls = controlItems.map { it.uppercase() }.toSet()
        val hasCoreControl = normalizedControls.contains("SEARCH")
        val hasSecondaryControl = normalizedControls.any { it in setOf("NEXT", "SORT", "FILTER", "AUCTION") }

        return hasCoreControl && hasSecondaryControl
    }
}
