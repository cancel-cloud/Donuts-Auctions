package de.lukas.donutsauctions.model

data class RepositoryHealth(
    val status: RepositoryStatus,
    val message: String,
    val readOnly: Boolean,
    val lastMigrationSummary: MigrationSummary?
)

enum class RepositoryStatus {
    OK,
    READ_ONLY,
    ERROR
}
