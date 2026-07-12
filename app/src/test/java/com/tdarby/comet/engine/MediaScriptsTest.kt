package com.tdarby.comet.engine

import org.junit.Assert.assertTrue
import org.junit.Test

class MediaScriptsTest {
    @Test fun everyActionTargetsVideoAndAudio() {
        MediaAction.entries.forEach { action ->
            val script = MediaScripts.forAction(action)
            assertTrue(script.contains("video,audio"))
            assertTrue(script.contains("if(!v)return"))
        }
    }

    @Test fun playbackControlsCoverAllFiveButtonMediaOperations() {
        assertTrue(MediaScripts.forAction(MediaAction.PLAY_PAUSE).contains("v.play()"))
        assertTrue(MediaScripts.forAction(MediaAction.STOP).contains("currentTime=0"))
        assertTrue(MediaScripts.forAction(MediaAction.REWIND).contains("currentTime-10"))
        assertTrue(MediaScripts.forAction(MediaAction.FORWARD).contains("currentTime+10"))
        assertTrue(MediaScripts.PLAYBACK_PROBE.contains("currentTime"))
    }
}
