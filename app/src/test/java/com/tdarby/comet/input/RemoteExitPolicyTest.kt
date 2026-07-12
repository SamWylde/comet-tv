package com.tdarby.comet.input

import com.tdarby.comet.input.RemoteExitPolicy.Effect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteExitPolicyTest {

    private val policy = RemoteExitPolicy()

    @Test
    fun fourEligibleBackPressesExitWithWarningsAfterSecondAndThird() {
        var state = RemoteExitPolicy.State()

        val first = policy.reduce(state, RemoteKey.BACK, nowMs = 1_000, exitEligible = true)
        assertEquals(Effect.ExitSequenceStarted, first.effect)
        assertEquals(1, first.state.acceptedBackPresses)
        state = first.state

        val second = policy.reduce(state, RemoteKey.BACK, nowMs = 1_100, exitEligible = true)
        assertEquals(Effect.Warn(2), second.effect)
        assertEquals(2, second.state.acceptedBackPresses)
        state = second.state

        val third = policy.reduce(state, RemoteKey.BACK, nowMs = 1_200, exitEligible = true)
        assertEquals(Effect.Warn(1), third.effect)
        assertEquals(3, third.state.acceptedBackPresses)

        val fourth = policy.reduce(third.state, RemoteKey.BACK, nowMs = 1_300, exitEligible = true)
        assertEquals(Effect.Exit, fourth.effect)
        assertEquals(RemoteExitPolicy.State(), fourth.state)
    }

    @Test
    fun everyDirectionalAndEnterKeyClearsASequence() {
        val armed = policy.reduce(
            RemoteExitPolicy.State(),
            RemoteKey.BACK,
            nowMs = 10,
            exitEligible = true
        ).state

        RemoteKey.entries.filterNot { it == RemoteKey.BACK }.forEach { key ->
            val result = policy.reduce(armed, key, nowMs = 20, exitEligible = true)
            assertEquals("$key must reset exit state", RemoteExitPolicy.State(), result.state)
            assertEquals("$key must remain ordinary navigation", Effect.Continue, result.effect)
        }
    }

    @Test
    fun backConsumedByBrowserUiClearsASequence() {
        val armed = RemoteExitPolicy.State(acceptedBackPresses = 3, deadlineMs = 5_000)

        val result = policy.reduce(armed, RemoteKey.BACK, nowMs = 1_000, exitEligible = false)

        assertEquals(RemoteExitPolicy.State(), result.state)
        assertEquals(Effect.Continue, result.effect)
    }

    @Test
    fun expiredSequenceRestartsAtFirstPress() {
        val expired = RemoteExitPolicy.State(acceptedBackPresses = 3, deadlineMs = 999)

        val result = policy.reduce(expired, RemoteKey.BACK, nowMs = 1_000, exitEligible = true)

        assertEquals(Effect.ExitSequenceStarted, result.effect)
        assertEquals(1, result.state.acceptedBackPresses)
        assertEquals(7_000, result.state.deadlineMs)
    }

    @Test
    fun pressExactlyAtDeadlineStillBelongsToSequence() {
        val active = RemoteExitPolicy.State(acceptedBackPresses = 1, deadlineMs = 1_000)

        val result = policy.reduce(active, RemoteKey.BACK, nowMs = 1_000, exitEligible = true)

        assertEquals(Effect.Warn(2), result.effect)
        assertEquals(2, result.state.acceptedBackPresses)
    }

    @Test
    fun exhaustiveFiveEventSequencesOnlyExitAfterFourUninterruptedBacks() {
        // 6^5 = 7,776 sequences: small enough for every JVM run, broad enough to catch a D-pad or
        // ENTER accidentally being counted as BACK or failing to reset a partially armed exit.
        val keys = RemoteKey.entries
        var checked = 0
        enumerate(keys, length = 5, visit = { sequence ->
            var state = RemoteExitPolicy.State()
            var sawExit = false
            var trailingEligibleBacks = 0

            sequence.forEachIndexed { index, key ->
                val transition = policy.reduce(
                    state,
                    key,
                    nowMs = index * 100L,
                    exitEligible = true
                )
                state = transition.state
                sawExit = sawExit || transition.effect == Effect.Exit

                trailingEligibleBacks = if (key == RemoteKey.BACK) trailingEligibleBacks + 1 else 0
                if (trailingEligibleBacks == 4) trailingEligibleBacks = 0 // Exit resets the policy.
            }

            assertEquals(sequence.toString(), containsFourConsecutiveBacks(sequence), sawExit)
            checked++
        })
        assertEquals(7_776, checked)
    }

    @Test
    fun noNonBackKeyCanExitEvenFromFullyArmedState() {
        val armed = RemoteExitPolicy.State(acceptedBackPresses = 3, deadlineMs = Long.MAX_VALUE)

        RemoteKey.entries.filterNot { it == RemoteKey.BACK }.forEach { key ->
            assertFalse(
                "$key unexpectedly exited",
                policy.reduce(armed, key, nowMs = 0, exitEligible = true).effect == Effect.Exit
            )
        }
    }

    @Test
    fun invalidConfigurationIsRejected() {
        assertFails { RemoteExitPolicy(requiredBackPresses = 1) }
        assertFails { RemoteExitPolicy(timeoutMs = 0) }
    }

    private fun containsFourConsecutiveBacks(keys: List<RemoteKey>): Boolean {
        var run = 0
        keys.forEach { key ->
            run = if (key == RemoteKey.BACK) run + 1 else 0
            if (run >= 4) return true
        }
        return false
    }

    private fun enumerate(
        keys: List<RemoteKey>,
        length: Int,
        visit: (List<RemoteKey>) -> Unit,
        prefix: MutableList<RemoteKey> = mutableListOf()
    ) {
        if (prefix.size == length) {
            visit(prefix.toList())
            return
        }
        keys.forEach { key ->
            prefix.add(key)
            enumerate(keys, length, visit, prefix)
            prefix.removeAt(prefix.lastIndex)
        }
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("Expected IllegalArgumentException", failed)
    }
}
