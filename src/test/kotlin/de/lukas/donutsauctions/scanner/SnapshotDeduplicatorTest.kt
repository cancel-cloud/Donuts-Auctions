package de.lukas.donutsauctions.scanner

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotDeduplicatorTest {

    @Test
    fun `captures first and rejects immediate duplicate`() {
        val deduplicator = SnapshotDeduplicator()
        assertTrue(deduplicator.shouldCapture("hash-a"))
        assertFalse(deduplicator.shouldCapture("hash-a"))
        assertTrue(deduplicator.shouldCapture("hash-b"))
    }
}
