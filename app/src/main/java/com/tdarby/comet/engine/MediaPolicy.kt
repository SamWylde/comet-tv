package com.tdarby.comet.engine

import kotlin.math.abs

/**
 * Picks the player a remote command should act on. A page can hold an ad, a preview loop, background
 * music and the real player at once, so candidates are scored instead of taking the first element.
 */
object MediaPlayerChooser {
    /** Below this on-screen size (CSS pixels squared, about 140 x 140) a video is an ad or an icon. */
    const val TINY_AREA = 20_000L
    private const val AREA_CAP = 2_000_000L

    /** The best candidate, or null when there is none. Ties keep the earlier one (page order). */
    fun choose(candidates: List<Observed>): Observed? {
        var best: Observed? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (candidate in candidates) {
            val score = score(candidate.item)
            if (score > bestScore) {
                best = candidate
                bestScore = score
            }
        }
        return best
    }

    /**
     * Order of importance: playing, then not tiny or hidden, then on-screen area, then having
     * media loaded, then sound. A muted looping video is a page background, not a player.
     */
    fun score(item: MediaItem): Double {
        var score = 0.0
        if (item.playing) score += 4_000
        if (item.audioOnly) {
            // Audio has no picture to measure; a playing track still beats a hidden or tiny video.
            score += 600
        } else if (item.area >= TINY_AREA) {
            score += 1_000 + item.area.coerceAtMost(AREA_CAP) / 1_000.0
        } else {
            score -= 3_500
        }
        if (item.readyState >= 1 || item.duration != null) score += 300
        if (!item.muted) score += 150
        if (item.muted && item.loop) score -= 3_000
        if (item.error) score -= 5_000
        if (item.ended) score -= 200
        return score
    }
}

/** What to do for a command: send this change, refuse with a reason, or nothing at all. */
sealed interface MediaPlan {
    data class Send(val op: MediaOp) : MediaPlan
    data class Reject(val feedback: MediaFeedback) : MediaPlan
    data object Nothing : MediaPlan
}

/** Turns a command into a change the page can accept, using the element's real limits. */
object MediaPlanner {
    /** A recorded video is never sought closer than this to its end, which would end it. */
    const val END_MARGIN_SECONDS = 1.0
    private const val SAME_POSITION_SECONDS = 0.05
    private const val WINDOW_SLACK_SECONDS = 0.5

    fun plan(command: MediaCommand, item: MediaItem): MediaPlan = when (command) {
        MediaCommand.Refresh -> MediaPlan.Nothing
        MediaCommand.TogglePlay -> MediaPlan.Send(if (item.playing) MediaOp.Pause else MediaOp.Play)
        MediaCommand.Play -> if (item.playing) MediaPlan.Nothing else MediaPlan.Send(MediaOp.Play)
        MediaCommand.Pause -> if (item.paused) MediaPlan.Nothing else MediaPlan.Send(MediaOp.Pause)
        MediaCommand.Stop -> MediaPlan.Send(MediaOp.Stop)
        is MediaCommand.Seek -> seek(item, command.deltaSeconds)
        is MediaCommand.SetRate ->
            if (canChangeRate(item)) MediaPlan.Send(MediaOp.SetRate(command.rate))
            else MediaPlan.Reject(MediaFeedback.CANNOT_CHANGE_SPEED)
    }

    /** Where playback can move: the seekable range holding the position, else the last range. */
    fun seekWindow(item: MediaItem): SeekRange? {
        val ranges = item.seekable.filter { it.end > it.start }
        if (ranges.isNotEmpty()) {
            return ranges.firstOrNull {
                item.currentTime >= it.start - WINDOW_SLACK_SECONDS && item.currentTime <= it.end + WINDOW_SLACK_SECONDS
            } ?: ranges.last()
        }
        val duration = item.duration
        return if (duration != null && duration.isFinite() && duration > 0 && item.readyState >= 1) {
            SeekRange(0.0, duration)
        } else null
    }

    fun canSeek(item: MediaItem): Boolean = seekWindow(item) != null

    fun canChangeRate(item: MediaItem): Boolean = !item.live && !item.error && item.readyState >= 1

    private fun seek(item: MediaItem, delta: Double): MediaPlan {
        val window = seekWindow(item) ?: return MediaPlan.Reject(MediaFeedback.CANNOT_SEEK)
        val latest = if (item.live) window.end else maxOf(window.start, window.end - END_MARGIN_SECONDS)
        val target = (item.currentTime + delta).coerceIn(window.start, latest)
        return if (abs(target - item.currentTime) < SAME_POSITION_SECONDS) MediaPlan.Nothing
        else MediaPlan.Send(MediaOp.SeekTo(target))
    }

    fun statusOf(item: MediaItem): MediaStatus = MediaStatus(
        found = true,
        playing = item.playing,
        positionSeconds = item.currentTime,
        durationSeconds = item.duration?.takeIf { it.isFinite() && !item.live },
        live = item.live,
        rate = item.rate,
        canSeek = canSeek(item),
        canChangeRate = canChangeRate(item)
    )
}

/**
 * Spots a live stream that reports a finite duration: its seekable window keeps moving forward
 * about as fast as the clock, which a recorded video's never does.
 */
class LiveWindowDetector {
    private data class Sample(val atMs: Long, val end: Double, val moving: Boolean)

    private val samples = HashMap<String, Sample>()

    /** Records this look at a player and returns whether its window is moving like a live one. */
    fun observe(key: String, nowMs: Long, item: MediaItem): Boolean {
        val end = item.seekable.lastOrNull()?.end ?: run {
            samples.remove(key)
            return false
        }
        val previous = samples[key]
        if (previous == null) {
            if (samples.size >= MAX_TRACKED) samples.clear()
            samples[key] = Sample(nowMs, end, moving = false)
            return false
        }
        val elapsedMs = nowMs - previous.atMs
        if (elapsedMs < MIN_INTERVAL_MS) return previous.moving
        val advanced = end - previous.end
        val moving = advanced >= MIN_ADVANCE_SECONDS && advanced >= elapsedMs / 1000.0 * MIN_SHARE_OF_CLOCK
        samples[key] = Sample(nowMs, end, moving)
        return moving
    }

    private companion object {
        const val MAX_TRACKED = 8
        const val MIN_INTERVAL_MS = 300L
        const val MIN_ADVANCE_SECONDS = 0.3
        const val MIN_SHARE_OF_CLOCK = 0.5
    }
}

/** The playback speeds the video controls step through. */
object PlaybackSpeeds {
    val OPTIONS = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)

    /** The next option above [current], wrapping to the slowest after the fastest. */
    fun next(current: Double): Double = OPTIONS.firstOrNull { it > current + 0.01 } ?: OPTIONS.first()

    /** "1x", "0.75x", "1.5x". */
    fun label(rate: Double): String =
        String.format(java.util.Locale.US, "%.2f", rate).trimEnd('0').trimEnd('.') + "x"
}

/** Playback positions as people read them: 4:05, or 1:02:03 from an hour on. */
object MediaClock {
    fun format(seconds: Double): String {
        val total = if (seconds.isFinite() && seconds > 0) seconds.toLong() else 0L
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(java.util.Locale.US, "%d:%02d", m, s)
    }
}
