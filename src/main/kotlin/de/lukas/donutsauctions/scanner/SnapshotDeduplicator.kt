package de.lukas.donutsauctions.scanner

class SnapshotDeduplicator {
    private var lastHash: String? = null

    @Synchronized
    fun shouldCapture(snapshotHash: String): Boolean {
        if (snapshotHash == lastHash) {
            return false
        }
        lastHash = snapshotHash
        return true
    }

    @Synchronized
    fun reset() {
        lastHash = null
    }
}
