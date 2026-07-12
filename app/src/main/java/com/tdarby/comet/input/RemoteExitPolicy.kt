package com.tdarby.comet.input

/** The complete set of controls available on a basic Android TV remote. */
enum class RemoteKey {
    UP,
    DOWN,
    LEFT,
    RIGHT,
    ENTER,
    BACK
}

/**
 * Pure policy for Comet's guarded app-exit sequence.
 *
 * BrowserActivity decides whether BACK has already been consumed by a page, dialog, address field,
 * fullscreen player, or browser history. Only a BACK press at the browser root is [exitEligible].
 * Every other remote action clears a partially-entered exit sequence so ordinary navigation can
 * never accidentally contribute toward closing the app.
 */
class RemoteExitPolicy(
    private val requiredBackPresses: Int = DEFAULT_REQUIRED_BACK_PRESSES,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    init {
        require(requiredBackPresses >= 2) { "requiredBackPresses must be at least 2" }
        require(timeoutMs > 0) { "timeoutMs must be positive" }
    }

    data class State(
        val acceptedBackPresses: Int = 0,
        val deadlineMs: Long = 0L
    )

    data class Transition(
        val state: State,
        val effect: Effect
    )

    sealed interface Effect {
        /** Navigation continues normally and no exit feedback is required. */
        data object Continue : Effect

        /** BACK was accepted, but no warning is shown on the first press. */
        data object ExitSequenceStarted : Effect

        /** Tell the user exactly how many more consecutive BACK presses are required. */
        data class Warn(val remainingBackPresses: Int) : Effect

        /** The complete guarded sequence was entered and the Activity may finish. */
        data object Exit : Effect
    }

    fun reduce(
        state: State,
        key: RemoteKey,
        nowMs: Long,
        exitEligible: Boolean
    ): Transition {
        // D-pad/Enter activity and a BACK handled elsewhere both prove the user is navigating, not
        // continuing an exit request. Reset eagerly even if the prior deadline has not elapsed.
        if (key != RemoteKey.BACK || !exitEligible) {
            return Transition(State(), Effect.Continue)
        }

        val previousCount = if (state.acceptedBackPresses > 0 && nowMs <= state.deadlineMs) {
            state.acceptedBackPresses
        } else {
            0
        }
        val accepted = previousCount + 1

        if (accepted >= requiredBackPresses) {
            return Transition(State(), Effect.Exit)
        }

        val next = State(
            acceptedBackPresses = accepted,
            deadlineMs = nowMs + timeoutMs
        )
        return Transition(
            next,
            if (accepted == 1) {
                Effect.ExitSequenceStarted
            } else {
                Effect.Warn(requiredBackPresses - accepted)
            }
        )
    }

    companion object {
        const val DEFAULT_REQUIRED_BACK_PRESSES = 4
        const val DEFAULT_TIMEOUT_MS = 6_000L
    }
}
