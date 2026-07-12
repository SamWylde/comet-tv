package com.tdarby.comet.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.Choreographer
import android.widget.FrameLayout
import android.widget.ImageView
import com.tdarby.comet.R
import kotlin.math.min

/**
 * On-screen mouse cursor for D-pad remotes. Web pages (especially video players) aren't D-pad
 * focusable, so we float a pointer over the engine view, move it with the D-pad, and translate
 * OK presses into synthetic taps. Pushing the pointer against an edge scrolls the page via a
 * synthetic drag/scroll events, which the WebView consumes.
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
    private val pointerSize = dp(36)

    private val pointer = ImageView(container.context).apply {
        setImageResource(R.drawable.ic_cursor)
        layoutParams = FrameLayout.LayoutParams(pointerSize, pointerSize)
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
    private var motionDx = 0
    private var motionDy = 0
    private var motionStartMs = 0L
    private var lastFrameNanos = 0L
    private var framePosted = false
    private var lastEdgeScrollMs = 0L

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        framePosted = false
        if (!active || motionDx == 0 && motionDy == 0) return@FrameCallback
        val now = SystemClock.uptimeMillis()
        val velocityDp = CursorMotionProfile.velocityDpPerSecond(now - motionStartMs, speedFactor)
        if (velocityDp > 0f && lastFrameNanos > 0L) {
            val seconds = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            val distancePx = velocityDp * density * seconds
            moveBy(motionDx * distancePx, motionDy * distancePx, now - motionStartMs)
        }
        lastFrameNanos = frameTimeNanos
        postFrame()
    }

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
            stopMove()
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
        val maxX = (w - 1f).coerceAtLeast(0f)
        val maxY = (h - 1f).coerceAtLeast(0f)
        val edge = dp(2).toFloat()
        val sx = dx * dp(ANALOG_STEP) * speedFactor
        val sy = dy * dp(ANALOG_STEP) * speedFactor
        if (sy < 0 && y <= edge) scroll(0f, 1f, 4) else if (sy > 0 && y >= maxY - edge) scroll(0f, -1f, 4)
        if (sx < 0 && x <= edge) scroll(1f, 0f, 4) else if (sx > 0 && x >= maxX - edge) scroll(-1f, 0f, 4)
        x = (x + sx).coerceIn(0f, maxX)
        y = (y + sy).coerceIn(0f, maxY)
        updatePointer()
        bumpVisibility()
    }

    private fun center() {
        x = container.width / 2f
        y = container.height / 2f
    }

    /** Start frame-timed movement. Repeated key-down events do not create visible jumps. */
    fun startMove(dx: Int, dy: Int) {
        if (!active || dx == 0 && dy == 0) return
        if (motionDx == dx && motionDy == dy) return
        stopMove()
        motionDx = dx
        motionDy = dy
        motionStartMs = SystemClock.uptimeMillis()
        lastFrameNanos = 0L
        val precisionPx = CursorMotionProfile.PRECISION_STEP_DP * density
        moveBy(dx * precisionPx, dy * precisionPx, 0L)
        postFrame()
    }

    fun stopMove() {
        motionDx = 0
        motionDy = 0
        if (framePosted) Choreographer.getInstance().removeFrameCallback(frameCallback)
        framePosted = false
        lastFrameNanos = 0L
    }

    private fun postFrame() {
        if (framePosted || motionDx == 0 && motionDy == 0) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /** Move by a subpixel delta; edge contact scrolls the page instead of overflowing. */
    private fun moveBy(deltaX: Float, deltaY: Float, heldMs: Long) {
        if (container.width == 0) return
        if (x < 0f || y < 0f) center()
        val w = container.width.toFloat()
        val h = container.height.toFloat()
        val maxX = (w - 1f).coerceAtLeast(0f)
        val maxY = (h - 1f).coerceAtLeast(0f)
        val edge = dp(2).toFloat()

        val now = SystemClock.uptimeMillis()
        if (now - lastEdgeScrollMs >= EDGE_SCROLL_INTERVAL_MS) {
            val repeatEquivalent = (heldMs / 50L).toInt()
            if (deltaY < 0 && y <= edge) scroll(0f, 1f, repeatEquivalent)
            else if (deltaY > 0 && y >= maxY - edge) scroll(0f, -1f, repeatEquivalent)
            if (deltaX < 0 && x <= edge) scroll(1f, 0f, repeatEquivalent)
            else if (deltaX > 0 && x >= maxX - edge) scroll(-1f, 0f, repeatEquivalent)
            lastEdgeScrollMs = now
        }

        x = (x + deltaX).coerceIn(0f, maxX)
        y = (y + deltaY).coerceIn(0f, maxY)
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

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        const val AUTO_HIDE_MS = 5000L
        const val ANALOG_STEP = 22
        const val EDGE_SCROLL_INTERVAL_MS = 50L
    }
}
