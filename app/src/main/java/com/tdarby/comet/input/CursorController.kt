package com.tdarby.comet.input

import android.os.Handler
import android.os.Looper
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

    /** Cursor movement multiplier (driven by the Settings slider). */
    var speedFactor: Float = 1f

    private var x = -1f
    private var y = -1f

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { pointer.visibility = View.GONE }

    init {
        container.addView(pointer)
    }

    fun setActive(value: Boolean) {
        active = value
        if (value) {
            if (x < 0f || y < 0f) center()
            updatePointer()
            bumpVisibility()
        } else {
            hideHandler.removeCallbacks(hideRunnable)
            pointer.visibility = View.GONE
        }
    }

    /** Show the pointer and (re)arm the idle auto-hide, TV Bro-style. */
    private fun bumpVisibility() {
        if (!active) return
        pointer.visibility = View.VISIBLE
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, AUTO_HIDE_MS)
    }

    /** Analog (gamepad/joystick) cursor movement; [dx]/[dy] are −1..1 stick deflections. */
    fun nudge(dx: Float, dy: Float) {
        if (container.width == 0) return
        if (x < 0f || y < 0f) center()
        val w = container.width.toFloat()
        val h = container.height.toFloat()
        val edge = dp(2).toFloat()
        val sx = dx * dp(ANALOG_STEP) * speedFactor
        val sy = dy * dp(ANALOG_STEP) * speedFactor
        if (sy < 0 && y <= edge) scroll(0f, 1f, 4) else if (sy > 0 && y >= h - edge) scroll(0f, -1f, 4)
        if (sx < 0 && x <= edge) scroll(1f, 0f, 4) else if (sx > 0 && x >= w - edge) scroll(-1f, 0f, 4)
        x = (x + sx).coerceIn(0f, w)
        y = (y + sy).coerceIn(0f, h)
        updatePointer()
        bumpVisibility()
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

        // Pushing the pointer against an edge scrolls the page. Mouse-wheel events let Chromium run
        // its own smooth, animated scrolling (and route to whatever element is under the pointer),
        // instead of the jerky one-shot synthetic drag this used to do.
        if (dy < 0 && y <= edge) scroll(0f, 1f, repeatCount)
        else if (dy > 0 && y >= h - edge) scroll(0f, -1f, repeatCount)
        if (dx < 0 && x <= edge) scroll(1f, 0f, repeatCount)
        else if (dx > 0 && x >= w - edge) scroll(-1f, 0f, repeatCount)

        x = (x + dx * step).coerceIn(0f, w)
        y = (y + dy * step).coerceIn(0f, h)
        updatePointer()
        bumpVisibility()
    }

    /** True when the pointer is at the top edge of the content area (used to exit to the toolbar). */
    fun atTopEdge(): Boolean = y in 0f..dp(2).toFloat()

    /** Cursor position in CSS pixels (device px / density) for JS `elementFromPoint`. */
    fun cssX(): Float = (if (x < 0f) 0f else x) / density
    fun cssY(): Float = (if (y < 0f) 0f else y) / density

    /** Synthetic tap at the pointer. */
    fun click() {
        val target = targetProvider() ?: return
        val now = SystemClock.uptimeMillis()
        dispatch(target, MotionEvent.ACTION_DOWN, x, y, now, now)
        dispatch(target, MotionEvent.ACTION_UP, x, y, now, now + 60)
        bumpVisibility()
    }

    /**
     * Mouse-wheel scroll at the pointer. A positive [vScroll] scrolls up, negative scrolls down
     * (Android wheel convention); Chromium animates these smoothly. [repeatCount] accelerates the
     * notch size while a direction is held so long holds cover ground without feeling steppy.
     */
    private fun scroll(hScroll: Float, vScroll: Float, repeatCount: Int) {
        val target = targetProvider() ?: return
        val mag = 1f + min(repeatCount, 20) * 0.4f
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll * mag)
            setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll * mag)
        })
        val ev = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0
        )
        target.dispatchGenericMotionEvent(ev)
        ev.recycle()
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

    /** Acceleration: small steps for taps, faster while a direction is held, scaled by speed. */
    private fun stepFor(repeatCount: Int): Float =
        (dp(14) + min(repeatCount, 24) * dp(3).toFloat()) * speedFactor

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        const val AUTO_HIDE_MS = 5000L
        const val ANALOG_STEP = 22
    }
}
