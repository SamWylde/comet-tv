package com.tdarby.comet.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoControlsKeysTest {
    @Test fun dpadDrivesTheVideoWhenHiddenAndThePanelWhenShowing() {
        val left = KeyEvent.KEYCODE_DPAD_LEFT
        val right = KeyEvent.KEYCODE_DPAD_RIGHT
        val up = KeyEvent.KEYCODE_DPAD_UP
        val down = KeyEvent.KEYCODE_DPAD_DOWN
        val ok = KeyEvent.KEYCODE_DPAD_CENTER
        // key, panel showing, remote control of fullscreen video on, expected
        val cases = listOf(
            listOf(ok, false, true, VideoKeyAction.TOGGLE_PLAY_AND_SHOW),
            listOf(KeyEvent.KEYCODE_ENTER, false, true, VideoKeyAction.TOGGLE_PLAY_AND_SHOW),
            listOf(left, false, true, VideoKeyAction.SEEK_BACK_AND_SHOW),
            listOf(right, false, true, VideoKeyAction.SEEK_FORWARD_AND_SHOW),
            listOf(up, false, true, VideoKeyAction.SHOW),
            listOf(down, false, true, VideoKeyAction.SHOW),
            listOf(left, true, true, VideoKeyAction.FOCUS_PREVIOUS),
            listOf(right, true, true, VideoKeyAction.FOCUS_NEXT),
            listOf(ok, true, true, VideoKeyAction.ACTIVATE),
            listOf(up, true, true, VideoKeyAction.KEEP_VISIBLE),
            // Setting off: the site's player keeps every key.
            listOf(ok, false, false, VideoKeyAction.NONE),
            listOf(left, false, false, VideoKeyAction.NONE),
            // The panel opened from the menu works with the setting off too.
            listOf(right, true, false, VideoKeyAction.FOCUS_NEXT),
            listOf(KeyEvent.KEYCODE_MENU, true, true, VideoKeyAction.NONE),
            listOf(KeyEvent.KEYCODE_BACK, false, true, VideoKeyAction.NONE),
            // BACK closes the panel itself: WebView's own fullscreen BACK handling must not see it.
            listOf(KeyEvent.KEYCODE_BACK, true, true, VideoKeyAction.HIDE),
        )
        cases.forEach { (key, showing, remote, expected) ->
            assertEquals("key $key showing=$showing remote=$remote", expected,
                VideoControlsKeys.decide(key as Int, showing as Boolean, remote as Boolean))
        }
    }
}
