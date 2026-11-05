package com.tdarby.comet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResumePolicyTest {

    private fun record(url: String, at: Long, position: Double = 600.0, duration: Double = 3000.0) =
        PlaybackRecord(url, "t", position, duration, at)

    @Test fun actionDependsOnLengthLiveAndEdges() {
        // position, duration, live, expected
        val cases = listOf(
            listOf(600.0, 3000.0, false, ResumeAction.SAVE),
            listOf(10.0, 3000.0, false, ResumeAction.DROP),       // barely started
            listOf(2980.0, 3000.0, false, ResumeAction.DROP),     // finished
            listOf(600.0, 299.0, false, ResumeAction.IGNORE),     // too short to track
            listOf(600.0, 3000.0, true, ResumeAction.IGNORE),     // live
            listOf(600.0, Double.POSITIVE_INFINITY, false, ResumeAction.IGNORE)
        )
        for (c in cases) {
            assertEquals(c.toString(), c[3], PlaybackResumePolicy.action(c[0] as Double, c[1] as Double, c[2] as Boolean))
        }
    }

    @Test fun periodicSaveWaitsForTheIntervalUnlessForced() {
        assertTrue(PlaybackResumePolicy.saveDue(0L, 1_000L, false))
        assertFalse(PlaybackResumePolicy.saveDue(1_000L, 10_000L, false))
        assertTrue(PlaybackResumePolicy.saveDue(1_000L, 16_000L, false))
        assertTrue(PlaybackResumePolicy.saveDue(1_000L, 2_000L, true))
    }

    @Test fun loadedVideoMustMatchRecordedLengthAndBeBehindTheSpot() {
        val r = record("u", 1, position = 600.0, duration = 3000.0)
        assertTrue(PlaybackResumePolicy.shouldOffer(r, 3001.5, 0.0))
        assertFalse(PlaybackResumePolicy.shouldOffer(r, 3003.0, 0.0))   // another episode
        assertFalse(PlaybackResumePolicy.shouldOffer(r, 3000.0, 595.0)) // already at the spot
        assertFalse(PlaybackResumePolicy.shouldOffer(r, Double.NaN, 0.0))
    }

    @Test fun recordsKeepOnePerPageNewestFirstAndAtMostThirty() {
        var list = emptyList<PlaybackRecord>()
        for (i in 1..35) list = PlaybackResumePolicy.upsert(list, record("p$i", i.toLong()))
        assertEquals(30, list.size)
        assertEquals("p35", list.first().url)
        assertFalse(list.any { it.url == "p5" }) // the five oldest were dropped

        val again = PlaybackResumePolicy.upsert(list, record("p10", 100L, position = 900.0))
        assertEquals(30, again.size)
        assertEquals("p10", again.first().url)
        assertEquals(1, again.count { it.url == "p10" })
        assertEquals(900.0, again.first().position, 0.0)
    }

    @Test fun clockAndMinutesLeft() {
        assertEquals("12:34", PlaybackResumePolicy.clock(754.0))
        assertEquals("1:05:09", PlaybackResumePolicy.clock(3909.0))
        assertEquals(41, PlaybackResumePolicy.minutesLeft(record("u", 1, position = 540.0, duration = 3000.0)))
        assertEquals("a", PlaybackResumePolicy.key("a#frag"))
    }
}
