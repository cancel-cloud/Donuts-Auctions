package de.lukas.donutsauctions.scanner

import net.minecraft.client.network.ClientPlayerEntity
import kotlin.math.abs

class PlayerIdleTracker {

    private var lastMovementAtMs: Long = 0L
    private var lastSample: MotionSample? = null

    fun update(player: ClientPlayerEntity, nowMs: Long) {
        updateSample(
            x = player.x,
            y = player.y,
            z = player.z,
            vx = player.velocity.x,
            vy = player.velocity.y,
            vz = player.velocity.z,
            onGround = player.isOnGround,
            nowMs = nowMs
        )
    }

    internal fun updateSample(
        x: Double,
        y: Double,
        z: Double,
        vx: Double,
        vy: Double,
        vz: Double,
        onGround: Boolean,
        nowMs: Long
    ) {
        // Intentionally ignore yaw/pitch; looking around should not cancel idle state.
        val current = MotionSample(
            x = x,
            y = y,
            z = z,
            vx = vx,
            vy = vy,
            vz = vz,
            onGround = onGround
        )

        val previous = lastSample
        if (previous == null) {
            lastSample = current
            lastMovementAtMs = nowMs
            return
        }

        val dx = current.x - previous.x
        val dy = current.y - previous.y
        val dz = current.z - previous.z
        val dvx = current.vx - previous.vx
        val dvy = current.vy - previous.vy
        val dvz = current.vz - previous.vz

        val horizontalPosDeltaSq = dx * dx + dz * dz
        val verticalPosDelta = abs(dy)
        val horizontalVelocityDeltaSq = dvx * dvx + dvz * dvz
        val verticalVelocityDelta = abs(dvy)
        val horizontalSpeedSq = current.vx * current.vx + current.vz * current.vz
        val verticalSpeed = abs(current.vy)
        val allowVerticalCheck = !current.onGround || !previous.onGround

        val movedByHorizontalPosition = horizontalPosDeltaSq > HORIZONTAL_POSITION_EPSILON_SQUARED
        val movedByVerticalPosition = allowVerticalCheck && verticalPosDelta > VERTICAL_POSITION_EPSILON
        val movedByHorizontalVelocityDelta = horizontalVelocityDeltaSq > HORIZONTAL_VELOCITY_DELTA_EPSILON_SQUARED
        val movedByVerticalVelocityDelta = allowVerticalCheck && verticalVelocityDelta > VERTICAL_VELOCITY_DELTA_EPSILON
        val movingByHorizontalSpeed = horizontalSpeedSq > HORIZONTAL_SPEED_EPSILON_SQUARED
        val movingByVerticalSpeed = allowVerticalCheck && verticalSpeed > VERTICAL_SPEED_EPSILON

        if (
            movedByHorizontalPosition ||
            movedByVerticalPosition ||
            movedByHorizontalVelocityDelta ||
            movedByVerticalVelocityDelta ||
            movingByHorizontalSpeed ||
            movingByVerticalSpeed
        ) {
            lastMovementAtMs = nowMs
        }

        lastSample = current
    }

    fun isIdle(nowMs: Long, idleDelayMs: Long): Boolean {
        if (lastSample == null) {
            return false
        }
        return (nowMs - lastMovementAtMs) >= idleDelayMs
    }

    fun remainingIdleMs(nowMs: Long, idleDelayMs: Long): Long {
        if (lastSample == null) {
            return idleDelayMs
        }
        val elapsed = nowMs - lastMovementAtMs
        return (idleDelayMs - elapsed).coerceAtLeast(0L)
    }

    fun reset(nowMs: Long = 0L) {
        lastMovementAtMs = nowMs
        lastSample = null
    }

    private data class MotionSample(
        val x: Double,
        val y: Double,
        val z: Double,
        val vx: Double,
        val vy: Double,
        val vz: Double,
        val onGround: Boolean
    )

    companion object {
        // Tuned to ignore tiny server-side jitter while still detecting real movement quickly.
        private const val HORIZONTAL_POSITION_EPSILON_SQUARED = 0.000025 // ~0.005 blocks
        private const val VERTICAL_POSITION_EPSILON = 0.01
        private const val HORIZONTAL_VELOCITY_DELTA_EPSILON_SQUARED = 0.0004 // ~0.02 b/tick
        private const val VERTICAL_VELOCITY_DELTA_EPSILON = 0.03
        private const val HORIZONTAL_SPEED_EPSILON_SQUARED = 0.0004 // ~0.02 b/tick
        private const val VERTICAL_SPEED_EPSILON = 0.03
    }
}
