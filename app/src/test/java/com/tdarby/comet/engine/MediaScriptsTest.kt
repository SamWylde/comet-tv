package com.tdarby.comet.engine

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}

/**
 * The decisions behind remote video control. The page-side JavaScript only reports what each
 * player looks like and does what it is told; choosing a player, clamping a seek, spotting a live
 * stream and trusting a page's messages are decided here, so they can be tested without a browser.
 */
class MediaPlaybackTest {
    private val ok = "0,1,12.5,600,1,230400,4,0:600"

    private fun item(
        index: Int = 0,
        paused: Boolean = false,
        muted: Boolean = false,
        loop: Boolean = false,
        error: Boolean = false,
        audioOnly: Boolean = false,
        time: Double = 100.0,
        duration: Double? = 600.0,
        area: Long = 900_000,
        readyState: Int = 4,
        seekable: List<SeekRange> = listOf(SeekRange(0.0, 600.0))
    ) = MediaItem(
        index = index, paused = paused, ended = false, muted = muted, error = error,
        audioOnly = audioOnly, loop = loop, currentTime = time, duration = duration, rate = 1.0,
        area = area, readyState = readyState, seekable = seekable
    )

    @Test fun chooserPicksTheRealPlayerNotAnAdOrABackgroundLoop() {
        val player = item(index = 0)
        val tinyAd = item(index = 1, area = 90 * 60)
        val backgroundLoop = item(index = 2, muted = true, loop = true, area = 2_000_000)
        val pausedPlayer = item(index = 3, paused = true)
        val brokenPlaying = item(index = 4, error = true)
        val podcast = item(index = 5, audioOnly = true, area = 0)
        val cases = listOf(
            "playing player beats an ad listed first" to (listOf(tinyAd, player) to 0),
            "playing player beats a muted background loop" to (listOf(backgroundLoop, player) to 0),
            "a paused player beats a playing tiny ad" to (listOf(tinyAd, pausedPlayer) to 3),
            "a failed player loses to a working one" to (listOf(brokenPlaying, pausedPlayer) to 3),
            "a playing track beats a paused video" to (listOf(pausedPlayer, podcast) to 5),
            "the larger of two players wins" to
                (listOf(item(index = 6, area = 100_000), item(index = 7, area = 800_000)) to 7),
            "the first of equal players wins" to (listOf(item(index = 8), item(index = 9)) to 8),
        )
        cases.forEach { (name, case) ->
            val (items, expected) = case
            val chosen = MediaPlayerChooser.choose(items.mapIndexed { i, it -> Observed("f$i", it) })
            assertEquals(name, expected, chosen?.item?.index)
        }
        assertNull(MediaPlayerChooser.choose(emptyList()))
    }

    @Test fun seekStaysInsideWhatThePlayerCanReach() {
        fun result(delta: Double, item: MediaItem): Any =
            when (val plan = MediaPlanner.plan(MediaCommand.Seek(delta), item)) {
                is MediaPlan.Send -> (plan.op as MediaOp.SeekTo).seconds
                is MediaPlan.Reject -> plan.feedback
                MediaPlan.Nothing -> "nothing"
            }
        val live = item(
            time = 5_000.0, duration = Double.POSITIVE_INFINITY,
            seekable = listOf(SeekRange(4_900.0, 5_020.0))
        )
        val cases = listOf(
            "middle" to (110.0 to result(10.0, item(time = 100.0))),
            "before the start" to (0.0 to result(-10.0, item(time = 4.0))),
            "past the end stops short of ending the video" to
                (599.0 to result(10.0, item(time = 595.0))),
            "recorded video at the very start" to ("nothing" to result(-10.0, item(time = 0.0))),
            "live: back inside the window" to (4_900.0 to result(-300.0, live)),
            "live: forward stops at the live edge" to (5_020.0 to result(60.0, live)),
            "length unknown, nothing seekable" to (MediaFeedback.CANNOT_SEEK to
                result(10.0, item(duration = null, readyState = 0, seekable = emptyList()))),
            "stream without a seekable range" to (MediaFeedback.CANNOT_SEEK to
                result(10.0, item(duration = Double.POSITIVE_INFINITY, seekable = emptyList()))),
            "only the range holding the position counts" to
                ((60.0 - MediaPlanner.END_MARGIN_SECONDS) to result(
                    50.0, item(time = 30.0, seekable = listOf(SeekRange(0.0, 60.0), SeekRange(200.0, 300.0)))
                )),
        )
        cases.forEach { (name, case) -> assertEquals(name, case.first, case.second) }
    }

    @Test fun speedChangesOnlyForRecordedMedia() {
        val change = MediaCommand.SetRate(1.5)
        assertEquals(MediaPlan.Send(MediaOp.SetRate(1.5)), MediaPlanner.plan(change, item()))
        assertEquals(
            MediaPlan.Reject(MediaFeedback.CANNOT_CHANGE_SPEED),
            MediaPlanner.plan(change, item(duration = Double.POSITIVE_INFINITY))
        )
    }

    @Test fun aSeekableWindowThatKeepsMovingIsLive() {
        fun at(end: Double) = item(seekable = listOf(SeekRange(0.0, end)))
        val detector = LiveWindowDetector()
        assertFalse(detector.observe("a", 0, at(100.0)))
        assertTrue("window end advanced with the clock", detector.observe("a", 1_000, at(101.0)))
        assertFalse("window end stood still", detector.observe("a", 2_000, at(101.0)))
        val recorded = LiveWindowDetector().apply { observe("v", 0, at(600.0)) }
        assertFalse("a recorded video's window never moves", recorded.observe("v", 5_000, at(600.0)))
    }

    @Test fun pageMessagesAreAcceptedOnlyInTheExactShape() {
        val cases = listOf(
            "H" to true,
            "R7;$ok" to true,
            "R7" to true,
            "A7;1;$ok" to true,
            "A7;0;" to true,
            "R7;0,0,-,i,1,0,1,10:20/30:40" to true,
            "R7;0,0,5,i,1,0,1,20:10" to false,
            "R7;" + List(MediaWire.MAX_ITEMS + 1) { ok }.joinToString(";") to false,
            "R7;" + List(MediaWire.MAX_ITEMS) { ok }.joinToString(";") to true,
            "R7;0,1,NaN,600,1,0,4," to false,
            "R7;0,1,1,600,0,0,4," to false,
            "R7;0,1,1,600,1,0,9," to false,
            "R7;0,999,1,600,1,0,4," to false,
            "R7;0,1,1e400,600,1,0,4," to false,
            "R7;0,1,1,600,1,0,4,0:1/2:3/4:5/6:7" to false,
            "Rx;$ok" to false,
            "A7;2;$ok" to false,
            "A7;1" to false,
            "Hello" to false,
            "javascript:alert(1)" to false,
            "R7;" + "x".repeat(MediaWire.MAX_CHARS) to false,
            "" to false,
        )
        cases.forEach { (raw, valid) -> assertEquals(raw.take(60), valid, MediaWire.parse(raw) != null) }
    }

    @Test fun speedStepsWrapAndTimesReadAsPlayersShowThem() {
        val steps = listOf(0.5 to 0.75, 1.0 to 1.25, 1.1 to 1.25, 1.5 to 2.0, 2.0 to 0.5, 0.0 to 0.5)
        steps.forEach { (from, to) -> assertEquals("from $from", to, PlaybackSpeeds.next(from), 0.0) }
        assertEquals(listOf("0.5x", "1x", "1.25x", "2x"), listOf(0.5, 1.0, 1.25, 2.0).map(PlaybackSpeeds::label))
        val times = listOf(0.0 to "0:00", 5.9 to "0:05", 245.0 to "4:05", 3_723.0 to "1:02:03", Double.NaN to "0:00")
        times.forEach { (seconds, text) -> assertEquals(text, MediaClock.format(seconds)) }
    }

    private class FakeLink(var alive: Boolean = true) : FrameLink {
        override fun post(message: String): Boolean = alive
    }

    private class Timers {
        var now = 0L
        private val due = ArrayList<Triple<Long, () -> Unit, BooleanArray>>()
        fun schedule(delay: Long, task: () -> Unit): () -> Unit {
            val cancelled = booleanArrayOf(false)
            due += Triple(now + delay, task, cancelled)
            return { cancelled[0] = true }
        }
        fun advance(ms: Long) {
            now += ms
            due.filter { it.first <= now && !it.third[0] }.forEach { it.third[0] = true; it.second() }
        }
    }

    @Test fun hubUsesOnlyAnswersToOpenRequestsAndDropsFramesThatStopAnswering() {
        val timers = Timers()
        val hub = MediaFrameHub({ timers.now }, timers::schedule)
        val answering = FakeLink()
        val silent = FakeLink()
        val a = Any()
        val b = Any()

        hub.onMessage(Any(), "R1;$ok") { FakeLink() }
        assertEquals("only a hello registers a frame", 0, hub.frameCount)
        hub.onMessage(a, "H") { answering }
        hub.onMessage(b, "H") { silent }

        var result: List<Observed>? = null
        hub.query { result = it }
        hub.onMessage(a, "R999;$ok") { answering }
        assertNull("an answer to another request is ignored", result)
        hub.onMessage(a, "R1;$ok") { answering }
        assertNull("still waiting for the second frame", result)
        timers.advance(300)
        assertEquals("the silent frame is left out after the timeout", listOf("f0"), result?.map { it.frameKey })

        hub.query { result = it }
        timers.advance(300)
        assertEquals("a frame that missed twice is dropped", 1, hub.frameCount)

        answering.alive = false
        hub.query { result = it }
        assertEquals("a gone frame ends the query at once", emptyList<Observed>(), result)
        assertEquals(0, hub.frameCount)
    }

    @Test fun controllerActsOnTheChosenPlayerAndSaysWhyACommandDidNothing() {
        val ad = item(index = 0, area = 5_000)
        val player = item(index = 1, paused = true, time = 30.0)
        val acted = ArrayList<Pair<String, MediaOp>>()
        val transport = object : MediaTransport {
            override fun query(result: (List<Observed>) -> Unit) =
                result(listOf(Observed("f0", ad), Observed("f1", player)))

            override fun act(target: Observed, op: MediaOp, result: (MediaReply?) -> Unit) {
                acted += target.frameKey to op
                result(MediaReply(true, target.item.copy(paused = false)))
            }
        }
        val controller = MediaController(transport) { 0L }
        var outcome: MediaOutcome? = null

        controller.run(MediaCommand.TogglePlay) { outcome = it }
        assertEquals(listOf("f1" to MediaOp.Play), acted)
        assertTrue(outcome?.status?.playing == true)

        controller.run(MediaCommand.Seek(-100.0)) { outcome = it }
        assertEquals("seek is clamped to the start", "f1" to MediaOp.SeekTo(0.0), acted.last())

        val nothing = MediaController(object : MediaTransport {
            override fun query(result: (List<Observed>) -> Unit) = result(emptyList())
            override fun act(target: Observed, op: MediaOp, result: (MediaReply?) -> Unit) = result(null)
        }) { 0L }
        nothing.run(MediaCommand.TogglePlay) { outcome = it }
        assertEquals(MediaFeedback.NO_VIDEO, outcome?.feedback)
    }
}
