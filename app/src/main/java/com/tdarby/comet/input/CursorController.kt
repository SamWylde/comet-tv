package com.tdarby.comet.input

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.tdarby.comet.R
import kotlin.math.min

/**
 * On-screen mouse cursor for D-pad remotes. Web pages (especially video players) aren't D-pad
 * focusable, so we float a pointer over the engine view, move it with the D-pad, and translate
 * OK presses into synthetic taps. Pushing the pointer against an edge scrolls the page via a
 * synthetic drag. Engine-agnostic: both WebView and GeckoView consume the dispatched touches.
 *
 * @param container the engine container; the pointer is added on top and coordinates map 1:1 to
 *                  the engine view (which fills the container).
 * @param targetProvider supplies the current engine view to receive synthetic touches.
 */
class CursorController(
    private val container: FrameLayout,
    private val targetProvider: () -> View?
) {
    private val density = container.resources.displayMetrics.density

    private val pointer = ImageView(container.context).apply {
        setImageResource(R.drawable.ic_cursor)
        val size = dp(28)
        layoutParams = FrameLayout.LayoutParams(size, size)
        visibility = View.GONE
        elevation = 1000f
        isClickable = false
        isFocusable = false
    }

    var active: Boolean = false
        private set

    private var x = -1f
    private var y = -1f

    init {
        container.addView(pointer)
    }

    fun setActive(value: Boolean) {
        active = value
        pointer.visibility = if (value) View.VISIBLE else View.GONE
        if (value) {
            if (x < 0f || y < 0f) center()
            updatePointer()
        }
    }

    private fun center() {
        x = container.width / 2f
        y = container.height / 2f
    }

    /** Move the pointer; edge contact scrolls the page instead of overflowing. */
    fun move(dx: Int, dy: Int, repeatCount: Int) {
        if (container.width == 0) return
        if (x < 0f || y < 0f) center()
        val step = stepFor(repeatCount)
        val w = container.width.toFloat()
        val h = container.height.toFloat()
        val edge = dp(2).toFloat()

        if (dy < 0 && y <= edge) scrollVertically(-1)
        else if (dy > 0 && y >= h - edge) scrollVertically(1)
        if (dx < 0 && x <= edge) scrollHorizontally(-1)
        else if (dx > 0 && x >= w - edge) scrollHorizontally(1)

        x = (x + dx * step).coerceIn(0f, w)
        y = (y + dy * step).coerceIn(0f, h)
        updatePointer()
    }

    /** Synthetic tap at the pointer. */
    fun click() {
        val target = targetProvider() ?: return
        val now = SystemClock.uptimeMillis()
        dispatch(target, MotionEvent.ACTION_DOWN, x, y, now, now)
        dispatch(target, MotionEvent.ACTION_UP, x, y, now, now + 60)
    }

    private fun scrollVertically(dir: Int) {
        val target = targetProvider() ?: return
        val cx = x.coerceIn(dp(20).toFloat(), container.width - dp(20).toFloat())
        val midY = container.height / 2f
        val amount = dp(220).toFloat()
        // Scroll down (dir>0) => content moves up => finger drags upward (end Y smaller).
        drag(target, cx, midY, cx, midY - dir * amount)
    }

    private fun scrollHorizontally(dir: Int) {
        val target = targetProvider() ?: return
        val cy = y.coerceIn(dp(20).toFloat(), container.height - dp(20).toFloat())
        val midX = container.width / 2f
        val amount = dp(220).toFloat()
        drag(target, midX, cy, midX - dir * amount, cy)
    }

    private fun drag(target: View, x1: Float, y1: Float, x2: Float, y2: Float) {
        val down = SystemClock.uptimeMillis()
        dispatch(target, MotionEvent.ACTION_DOWN, x1, y1, down, down)
        val steps = 8
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            dispatch(
                target, MotionEvent.ACTION_MOVE,
                x1 + (x2 - x1) * t, y1 + (y2 - y1) * t,
                down, down + i * 8L
            )
        }
        dispatch(target, MotionEvent.ACTION_UP, x2, y2, down, down + steps * 8L + 8L)
    }

    private fun dispatch(target: View, action: Int, px: Float, py: Float, downTime: Long, eventTime: Long) {
        val ev = MotionEvent.obtain(downTime, eventTime, action, px, py, 0)
        ev.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        target.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun updatePointer() {
        // Arrow hotspot is the top-left, so place the image's top-left at (x, y).
        pointer.translationX = x
        pointer.translationY = y
    }

    /** Acceleration: small steps for taps, faster while a direction is held. */
    private fun stepFor(repeatCount: Int): Float = dp(14) + min(repeatCount, 24) * dp(3).toFloat()

    private fun dp(value: Int): Int = (value * density).toInt()
}
