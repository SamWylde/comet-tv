package com.tdarby.comet.ui

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.tdarby.comet.R
import com.tdarby.comet.engine.BrowserEngine
import com.tdarby.comet.engine.MediaClock
import com.tdarby.comet.engine.MediaCommand
import com.tdarby.comet.engine.MediaFeedback
import com.tdarby.comet.engine.MediaOutcome
import com.tdarby.comet.engine.MediaStatus
import com.tdarby.comet.engine.PlaybackSpeeds

/**
 * The on-screen video controls: play/pause, 10 s skips, speed and exit fullscreen, with the
 * position and clear feedback when something can't be done. It lives in [container], which is
 * stacked above the fullscreen video, and is driven entirely by the D-pad: [handleKey] decides
 * what each key does, the panel hides itself after a few seconds without input.
 *
 * @param engine the active tab's engine, looked up each time so tab switches are followed.
 * @param isFullscreen whether a page video is fullscreen (then the exit button shows).
 * @param exitFullscreen leaves fullscreen.
 * @param restoreFocus gives focus back to the page or the fullscreen video when the panel hides.
 */
class VideoControlsOverlay(
    private val container: FrameLayout,
    private val engine: () -> BrowserEngine?,
    private val isFullscreen: () -> Boolean,
    private val exitFullscreen: () -> Unit,
    private val restoreFocus: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val panel: View = LayoutInflater.from(container.context)
        .inflate(R.layout.view_video_controls, container, false)
    private val statusText: TextView = panel.findViewById(R.id.video_status)
    private val playButton: Button = panel.findViewById(R.id.video_btn_play)
    private val backButton: Button = panel.findViewById(R.id.video_btn_back)
    private val forwardButton: Button = panel.findViewById(R.id.video_btn_forward)
    private val speedButton: Button = panel.findViewById(R.id.video_btn_speed)
    private val exitButton: Button = panel.findViewById(R.id.video_btn_exit)

    private var status = MediaStatus.NONE
    private var message: MediaFeedback = MediaFeedback.NONE
    private var heldSeekKey = KeyEvent.KEYCODE_UNKNOWN
    private val pressed = HashSet<Int>()

    private val hideTask = Runnable { hide() }
    private val clearMessageTask = Runnable { message = MediaFeedback.NONE; render() }
    private val pollTask = object : Runnable {
        override fun run() {
            if (!isVisible) return
            run(MediaCommand.Refresh)
            handler.postDelayed(this, POLL_MS)
        }
    }

    val isVisible: Boolean get() = container.visibility == View.VISIBLE

    init {
        container.addView(panel)
        playButton.setOnClickListener { run(MediaCommand.TogglePlay) }
        backButton.setOnClickListener { run(MediaCommand.Seek(-SKIP_SECONDS)) }
        forwardButton.setOnClickListener { run(MediaCommand.Seek(SKIP_SECONDS)) }
        speedButton.setOnClickListener { run(MediaCommand.SetRate(PlaybackSpeeds.next(status.rate))) }
        exitButton.setOnClickListener {
            hide()
            exitFullscreen()
        }
        render()
    }

    /**
     * Handles a key before the rest of the app sees it. Returns true when it was a video-controls
     * key (both its down and up events are consumed, the work is done on the down).
     * @param remoteActive fullscreen video is on screen and the user left remote control on.
     */
    fun handleKey(event: KeyEvent, remoteActive: Boolean): Boolean {
        val keyCode = event.keyCode
        if (event.action == KeyEvent.ACTION_UP) {
            if (keyCode == heldSeekKey) heldSeekKey = KeyEvent.KEYCODE_UNKNOWN
            // The release of a key we used must not reach the app: it could click the page.
            return pressed.remove(keyCode)
        }
        var action = VideoControlsKeys.decide(keyCode, isVisible, remoteActive)
        // Holding LEFT/RIGHT keeps skipping even though the first press already raised the panel.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && keyCode == heldSeekKey) {
            action = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) VideoKeyAction.SEEK_BACK_AND_SHOW
            else VideoKeyAction.SEEK_FORWARD_AND_SHOW
        }
        if (action == VideoKeyAction.NONE) return false
        pressed += keyCode
        val isSeek = action == VideoKeyAction.SEEK_BACK_AND_SHOW || action == VideoKeyAction.SEEK_FORWARD_AND_SHOW
        val moves = action == VideoKeyAction.FOCUS_PREVIOUS || action == VideoKeyAction.FOCUS_NEXT
        if (event.repeatCount > 0 && !isSeek && !moves) return true
        when (action) {
            VideoKeyAction.TOGGLE_PLAY_AND_SHOW -> {
                show(playButton)
                run(MediaCommand.TogglePlay)
            }
            VideoKeyAction.SEEK_BACK_AND_SHOW -> {
                if (event.repeatCount == 0) heldSeekKey = keyCode
                show(backButton)
                run(MediaCommand.Seek(-SKIP_SECONDS))
            }
            VideoKeyAction.SEEK_FORWARD_AND_SHOW -> {
                if (event.repeatCount == 0) heldSeekKey = keyCode
                show(forwardButton)
                run(MediaCommand.Seek(SKIP_SECONDS))
            }
            VideoKeyAction.SHOW -> show(playButton)
            VideoKeyAction.FOCUS_PREVIOUS -> moveFocus(-1)
            VideoKeyAction.FOCUS_NEXT -> moveFocus(1)
            VideoKeyAction.ACTIVATE -> {
                restartHideTimer()
                (focusedButton() ?: playButton).performClick()
            }
            VideoKeyAction.KEEP_VISIBLE -> restartHideTimer()
            VideoKeyAction.NONE -> Unit
        }
        return true
    }

    /** Shows the panel (from the menu or a key) and starts following the player. */
    fun show(focusOn: Button = playButton) {
        val wasVisible = isVisible
        message = if (wasVisible) message else MediaFeedback.NONE
        container.visibility = View.VISIBLE
        exitButton.visibility = if (isFullscreen()) View.VISIBLE else View.GONE
        render()
        focusOn.requestFocus()
        restartHideTimer()
        if (!wasVisible) {
            run(MediaCommand.Refresh)
            handler.removeCallbacks(pollTask)
            handler.postDelayed(pollTask, POLL_MS)
        }
    }

    fun hide() {
        handler.removeCallbacks(hideTask)
        handler.removeCallbacks(pollTask)
        handler.removeCallbacks(clearMessageTask)
        heldSeekKey = KeyEvent.KEYCODE_UNKNOWN
        if (!isVisible) return
        container.visibility = View.GONE
        restoreFocus()
    }

    /** Stops timers; the activity is going away. */
    fun release() {
        handler.removeCallbacksAndMessages(null)
    }

    private fun run(command: MediaCommand) {
        val target = engine()
        if (target == null) {
            onOutcome(MediaOutcome.NO_VIDEO, command)
            return
        }
        target.mediaCommand(command) { onOutcome(it, command) }
    }

    private fun onOutcome(outcome: MediaOutcome, command: MediaCommand) {
        status = outcome.status
        // A background refresh must not wipe a message the user is still reading.
        if (outcome.feedback != MediaFeedback.NONE) {
            message = outcome.feedback
            handler.removeCallbacks(clearMessageTask)
            handler.postDelayed(clearMessageTask, MESSAGE_MS)
        } else if (command != MediaCommand.Refresh) {
            message = MediaFeedback.NONE
        }
        render()
    }

    private fun render() {
        playButton.setText(if (status.playing) R.string.video_pause else R.string.video_play)
        speedButton.visibility = if (status.canChangeRate) View.VISIBLE else View.GONE
        speedButton.text = panel.context.getString(R.string.video_speed, PlaybackSpeeds.label(status.rate))
        statusText.text = when {
            message != MediaFeedback.NONE -> panel.context.getString(feedbackText(message))
            !status.found -> panel.context.getString(R.string.video_no_video)
            else -> positionText()
        }
    }

    private fun positionText(): String {
        val current = status
        val position = MediaClock.format(current.positionSeconds)
        val duration = current.durationSeconds
        val where = when {
            current.live -> panel.context.getString(R.string.video_live)
            duration != null -> "$position / ${MediaClock.format(duration)}"
            else -> position
        }
        return if (current.rate != 1.0) "$where  ${PlaybackSpeeds.label(current.rate)}" else where
    }

    private fun feedbackText(feedback: MediaFeedback): Int = when (feedback) {
        MediaFeedback.NO_VIDEO -> R.string.video_no_video
        MediaFeedback.CANNOT_SEEK -> R.string.video_cannot_seek
        MediaFeedback.CANNOT_CHANGE_SPEED -> R.string.video_cannot_change_speed
        MediaFeedback.PLAY_BLOCKED -> R.string.video_play_blocked
        else -> R.string.video_failed
    }

    private fun buttons(): List<Button> =
        listOf(playButton, backButton, forwardButton, speedButton, exitButton).filter { it.visibility == View.VISIBLE }

    private fun focusedButton(): Button? = buttons().firstOrNull { it.hasFocus() }

    private fun moveFocus(step: Int) {
        val all = buttons()
        if (all.isEmpty()) return
        val current = all.indexOfFirst { it.hasFocus() }.takeIf { it >= 0 } ?: 0
        all[(current + step + all.size) % all.size].requestFocus()
        restartHideTimer()
    }

    private fun restartHideTimer() {
        handler.removeCallbacks(hideTask)
        handler.postDelayed(hideTask, HIDE_AFTER_MS)
    }

    private companion object {
        const val SKIP_SECONDS = 10.0
        const val HIDE_AFTER_MS = 5_000L
        const val POLL_MS = 1_000L
        const val MESSAGE_MS = 2_500L
    }
}
