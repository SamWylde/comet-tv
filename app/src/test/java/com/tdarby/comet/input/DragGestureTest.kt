package com.tdarby.comet.input

import com.tdarby.comet.input.DragGesture.Emit
import com.tdarby.comet.input.DragGesture.State
import org.junit.Assert.assertEquals
import org.junit.Test

class DragGestureTest {
    private fun run(vararg steps: (DragGesture) -> Emit?): Pair<List<Emit?>, State> {
        val g = DragGesture()
        val out = steps.map { it(g) }
        return out to g.state
    }

    private val enter: (DragGesture) -> Emit? = { it.enter(); null }
    private val ok: (DragGesture) -> Emit? = { it.ok() }
    private val move: (DragGesture) -> Emit? = { it.moved() }
    private val cancel: (DragGesture) -> Emit? = { it.cancel() }

    @Test fun pickUpMoveAndDrop() {
        val (emits, state) = run(enter, move, ok, move, move, ok)
        assertEquals(listOf(null, null, Emit.DOWN, Emit.MOVE, Emit.MOVE, Emit.UP), emits)
        assertEquals(State.OFF, state)
    }

    @Test fun cancelWhileDraggingSendsCancelAndNeverLeavesPointerDown() {
        val (emits, state) = run(enter, ok, move, cancel, move, ok)
        assertEquals(listOf(null, Emit.DOWN, Emit.MOVE, Emit.CANCEL, null, null), emits)
        assertEquals(State.OFF, state)
    }

    @Test fun cancelBeforePickUpEmitsNothing() {
        val (emits, state) = run(enter, cancel, ok)
        assertEquals(listOf(null, null, null), emits)
        assertEquals(State.OFF, state)
    }
}
