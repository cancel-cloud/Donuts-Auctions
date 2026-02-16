package de.lukas.donutsauctions.scanner

sealed interface AhCaptureResult {
    data class Noop(val reason: String) : AhCaptureResult
    data object Duplicate : AhCaptureResult
    data class Captured(
        val snapshotHash: String,
        val listingCount: Int,
        val capturedAt: Long,
        val page: Int?
    ) : AhCaptureResult
}
