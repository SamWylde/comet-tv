package com.tdarby.comet.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorMotionProfileTest {
    @Test fun tapStepIsSmallEnoughForTinyTvTargets() {
        assertTrue(CursorMotionProfile.PRECISION_STEP_DP <= 3f)
    }

    @Test fun heldMovementWaitsThenAcceleratesSmoothly() {
        assertEquals(0f, CursorMotionProfile.velocityDpPerSecond(0, 1f))
        assertEquals(0f, CursorMotionProfile.velocityDpPerSecond(179, 1f))
        val start = CursorMotionProfile.velocityDpPerSecond(180, 1f)
        val later = CursorMotionProfile.velocityDpPerSecond(500, 1f)
        val capped = CursorMotionProfile.velocityDpPerSecond(10_000, 1f)
        assertTrue(start > 0f)
        assertTrue(later > start)
        assertTrue(capped >= later)
    }

    @Test fun speedSettingScalesHeldMotionButNotPrecisionStep() {
        val slow = CursorMotionProfile.velocityDpPerSecond(600, 0.5f)
        val fast = CursorMotionProfile.velocityDpPerSecond(600, 2f)
        assertEquals(slow * 4f, fast)
        assertEquals(3f, CursorMotionProfile.PRECISION_STEP_DP)
    }
}
