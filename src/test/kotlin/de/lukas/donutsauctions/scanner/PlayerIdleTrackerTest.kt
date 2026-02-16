package de.lukas.donutsauctions.scanner

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerIdleTrackerTest {

    @Test
    fun `tiny jitter while standing still eventually counts as idle`() {
        val tracker = PlayerIdleTracker()
        tracker.updateSample(0.0, 64.0, 0.0, 0.0, 0.0, 0.0, onGround = true, nowMs = 0L)

        var now = 0L
        repeat(80) {
            now += 50L
            tracker.updateSample(
                x = 0.0008,
                y = 64.0,
                z = -0.0007,
                vx = 0.004,
                vy = 0.0,
                vz = -0.003,
                onGround = true,
                nowMs = now
            )
        }

        assertTrue(tracker.isIdle(nowMs = now, idleDelayMs = 3_000L))
    }

    @Test
    fun `real movement resets idle timer`() {
        val tracker = PlayerIdleTracker()
        tracker.updateSample(0.0, 64.0, 0.0, 0.0, 0.0, 0.0, onGround = true, nowMs = 0L)
        tracker.updateSample(0.0, 64.0, 0.0, 0.0, 0.0, 0.0, onGround = true, nowMs = 3_500L)
        assertTrue(tracker.isIdle(nowMs = 3_500L, idleDelayMs = 3_000L))

        tracker.updateSample(0.08, 64.0, 0.0, 0.08, 0.0, 0.0, onGround = true, nowMs = 3_600L)
        assertFalse(tracker.isIdle(nowMs = 3_600L, idleDelayMs = 3_000L))
        assertTrue(tracker.isIdle(nowMs = 6_700L, idleDelayMs = 3_000L))
    }
}
