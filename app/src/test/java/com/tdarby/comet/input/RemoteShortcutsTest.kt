package com.tdarby.comet.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteShortcutsTest {
    @Test fun navigationSystemAndMediaKeysCannotBeAssigned() {
        val blocked = listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_UNKNOWN
        )
        blocked.forEach { assertNull("key $it", RemoteShortcuts.EMPTY.with(it, RemoteAction.HOME)) }
        val allowed = listOf(
            KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_BOOKMARK, KeyEvent.KEYCODE_5,
            KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_TV, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SEARCH
        )
        allowed.forEach { assertTrue("key $it", RemoteShortcuts.isAssignable(it)) }
    }

    @Test fun jsonRoundTripReplaceAndRemove() {
        val s = RemoteShortcuts.EMPTY
            .with(KeyEvent.KEYCODE_PROG_RED, RemoteAction.TABS)!!
            .with(KeyEvent.KEYCODE_INFO, RemoteAction.RELOAD)!!
            .with(KeyEvent.KEYCODE_PROG_RED, RemoteAction.HOME)!!
        val back = RemoteShortcuts.fromJson(s.toJson())
        assertEquals(s.entries, back.entries)
        assertEquals(RemoteAction.HOME, back.actionFor(KeyEvent.KEYCODE_PROG_RED))
        assertNull(back.without(KeyEvent.KEYCODE_PROG_RED).actionFor(KeyEvent.KEYCODE_PROG_RED))
    }

    @Test fun storedGarbageUnknownActionsAndBlockedKeysAreDropped() {
        assertTrue(RemoteShortcuts.fromJson(null).isEmpty)
        assertTrue(RemoteShortcuts.fromJson("not json").isEmpty)
        val s = RemoteShortcuts.fromJson(
            "{\"183\":\"menu\",\"184\":\"no_such_action\",\"${KeyEvent.KEYCODE_BACK}\":\"home\"}"
        )
        assertEquals(listOf(183 to RemoteAction.MENU), s.entries)
    }
}
