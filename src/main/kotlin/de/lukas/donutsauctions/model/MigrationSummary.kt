package de.lukas.donutsauctions.model

data class MigrationSummary(
    val backupPath: String?,
    val duplicatesRemoved: Int,
    val shulkerRowsRemoved: Int,
    val legacyRowsRemoved: Int,
    val orphanSnapshotsRemoved: Int,
    val finalSnapshotCount: Int,
    val finalListingCount: Int,
    val vacuumed: Boolean
) {
    companion object {
        fun empty(): MigrationSummary {
            return MigrationSummary(
                backupPath = null,
                duplicatesRemoved = 0,
                shulkerRowsRemoved = 0,
                legacyRowsRemoved = 0,
                orphanSnapshotsRemoved = 0,
                finalSnapshotCount = 0,
                finalListingCount = 0,
                vacuumed = false
            )
        }
    }
}
