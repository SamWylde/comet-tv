package com.tdarby.comet.ui

import android.view.KeyEvent

/** What a remote key does while the video controls are in play. */
enum class VideoKeyAction {
    /** Not a video-controls key: the app handles it as usual. */
    NONE,
    TOGGLE_PLAY_AND_SHOW,
    SEEK_BACK_AND_SHOW,
    SEEK_FORWARD_AND_SHOW,
    SHOW,
    FOCUS_PREVIOUS,
    FOCUS_NEXT,
    ACTIVATE,
    KEEP_VISIBLE
}

/**
 * Key rules of the video controls. With the panel hidden and remote control of fullscreen video on,
 * OK toggles play, LEFT/RIGHT seek, UP/DOWN only bring the panel up. With the panel showing, the
 * D-pad moves between its buttons and OK presses the focused one.
 */
object VideoControlsKeys {
    fun decide(keyCode: Int, panelVisible: Boolean, remoteActive: Boolean): VideoKeyAction {
        val ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        return when {
            panelVisible -> when {
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> VideoKeyAction.FOCUS_PREVIOUS
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> VideoKeyAction.FOCUS_NEXT
                keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ->
                    VideoKeyAction.KEEP_VISIBLE
                ok -> VideoKeyAction.ACTIVATE
                else -> VideoKeyAction.NONE
            }
            !remoteActive -> VideoKeyAction.NONE
            ok -> VideoKeyAction.TOGGLE_PLAY_AND_SHOW
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> VideoKeyAction.SEEK_BACK_AND_SHOW
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> VideoKeyAction.SEEK_FORWARD_AND_SHOW
            keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> VideoKeyAction.SHOW
            else -> VideoKeyAction.NONE
        }
    }
}
