package com.tdarby.comet.engine

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaScriptsTest {
    @Test fun mediaKeysMapToActionsAndOnlySeekKeysRepeat() {
        val cases = listOf(
            Triple(KeyEvent.KEYCODE_MEDIA_PLAY, 0, MediaAction.PLAY),
            Triple(KeyEvent.KEYCODE_MEDIA_PAUSE, 0, MediaAction.PAUSE),
            Triple(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0, MediaAction.PLAY_PAUSE),
            Triple(KeyEvent.KEYCODE_MEDIA_STOP, 0, MediaAction.STOP),
            Triple(KeyEvent.KEYCODE_MEDIA_REWIND, 0, MediaAction.REWIND),
            Triple(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, 0, MediaAction.FORWARD),
            Triple(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 3, null),
            Triple(KeyEvent.KEYCODE_MEDIA_PLAY, 1, null),
            Triple(KeyEvent.KEYCODE_MEDIA_PAUSE, 1, null),
            Triple(KeyEvent.KEYCODE_MEDIA_STOP, 2, null),
            Triple(KeyEvent.KEYCODE_MEDIA_REWIND, 5, MediaAction.REWIND),
            Triple(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, 5, MediaAction.FORWARD),
            Triple(KeyEvent.KEYCODE_DPAD_CENTER, 0, null),
        )
        cases.forEach { (key, repeat, expected) ->
            assertEquals("key $key repeat $repeat", expected, MediaKeys.actionFor(key, repeat))
        }
    }

    @Test fun onlyMediaKeysAreMediaKeys() {
        assertTrue(MediaKeys.isMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertFalse(MediaKeys.isMediaKey(KeyEvent.KEYCODE_DPAD_CENTER))
    }
}
