package com.tdarby.comet.input

import kotlin.math.min

/** Pure movement curve shared by the cursor and its regression tests. */
object CursorMotionProfile {
    const val PRECISION_STEP_DP = 3f
    const val HOLD_DELAY_MS = 180L
    private const val BASE_SPEED_DP_PER_SECOND = 115f
    private const val ACCELERATION_DP_PER_SECOND_SQUARED = 720f
    private const val MAX_SPEED_DP_PER_SECOND = 680f

    fun velocityDpPerSecond(heldMs: Long, speedFactor: Float): Float {
        if (heldMs < HOLD_DELAY_MS) return 0f
        val acceleratingSeconds = (heldMs - HOLD_DELAY_MS) / 1_000f
        val velocity = BASE_SPEED_DP_PER_SECOND +
            acceleratingSeconds * ACCELERATION_DP_PER_SECOND_SQUARED
        return min(velocity, MAX_SPEED_DP_PER_SECOND) * speedFactor.coerceIn(0.25f, 3f)
    }
}
