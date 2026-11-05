package com.tdarby.comet.input

/**
 * Drag mode as a pure state machine: OFF -> ARMED (cursor placed) -> DRAGGING (pointer pressed) -> OFF.
 * Each event returns the touch event to send (or null). A pointer is only pressed in DRAGGING, and
 * every way out of DRAGGING emits UP or CANCEL, so it can never stay pressed.
 */
class DragGesture {
    enum class State { OFF, ARMED, DRAGGING }
    enum class Emit { DOWN, MOVE, UP, CANCEL }

    var state: State = State.OFF
        private set

    fun enter() {
        if (state == State.OFF) state = State.ARMED
    }

    /** OK: first press picks up, second press drops. */
    fun ok(): Emit? = when (state) {
        State.ARMED -> { state = State.DRAGGING; Emit.DOWN }
        State.DRAGGING -> { state = State.OFF; Emit.UP }
        State.OFF -> null
    }

    /** The cursor moved. */
    fun moved(): Emit? = if (state == State.DRAGGING) Emit.MOVE else null

    /** BACK, tab change, fullscreen, focus loss, pause, cursor off: leave drag mode from any state. */
    fun cancel(): Emit? {
        val wasDragging = state == State.DRAGGING
        state = State.OFF
        return if (wasDragging) Emit.CANCEL else null
    }
}
