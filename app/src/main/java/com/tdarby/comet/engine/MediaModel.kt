package com.tdarby.comet.engine

/** A seekable time range of a media element, in seconds. */
data class SeekRange(val start: Double, val end: Double)

/**
 * What one `<video>`/`<audio>` element reported. Everything here came from a page, so it is
 * always built through [MediaWire], which bounds and validates each field.
 */
data class MediaItem(
    val index: Int,
    val paused: Boolean,
    val ended: Boolean,
    val muted: Boolean,
    val error: Boolean,
    val audioOnly: Boolean,
    val loop: Boolean,
    val currentTime: Double,
    /** Seconds, [Double.POSITIVE_INFINITY] for a stream with no end, null when not known yet. */
    val duration: Double?,
    val rate: Double,
    /** On-screen area of the element in CSS pixels squared; 0 for audio or hidden elements. */
    val area: Long,
    val readyState: Int,
    val seekable: List<SeekRange>,
    /** Set by the controller when the seekable window moves like a live stream's. */
    val movingWindow: Boolean = false
) {
    val playing: Boolean get() = !paused && !ended
    val live: Boolean get() = duration == Double.POSITIVE_INFINITY || movingWindow
}

/** A media element together with the frame it lives in. */
data class Observed(val frameKey: String, val item: MediaItem)

/** A change the page is asked to make to one element. */
sealed interface MediaOp {
    data object Play : MediaOp
    data object Pause : MediaOp
    data object Stop : MediaOp
    data class SeekTo(val seconds: Double) : MediaOp
    data class SetRate(val rate: Double) : MediaOp
}

/** The page's answer to a [MediaOp]: whether it took effect, and the element's state afterwards. */
data class MediaReply(val ok: Boolean, val item: MediaItem?)

/** What the user or a remote key asks for. */
sealed interface MediaCommand {
    data object Refresh : MediaCommand
    data object TogglePlay : MediaCommand
    data object Play : MediaCommand
    data object Pause : MediaCommand
    data object Stop : MediaCommand
    data class Seek(val deltaSeconds: Double) : MediaCommand
    data class SetRate(val rate: Double) : MediaCommand
}

/** Why a command did nothing, so the screen can say so. */
enum class MediaFeedback { NONE, NO_VIDEO, CANNOT_SEEK, CANNOT_CHANGE_SPEED, PLAY_BLOCKED, FAILED }

/** The chosen player's state as the controls show it. */
data class MediaStatus(
    val found: Boolean,
    val playing: Boolean = false,
    val positionSeconds: Double = 0.0,
    /** Null for live streams and while the length is unknown. */
    val durationSeconds: Double? = null,
    val live: Boolean = false,
    val rate: Double = 1.0,
    val canSeek: Boolean = false,
    val canChangeRate: Boolean = false
) {
    companion object {
        val NONE = MediaStatus(found = false)
    }
}

data class MediaOutcome(val status: MediaStatus, val feedback: MediaFeedback = MediaFeedback.NONE) {
    companion object {
        val NO_VIDEO = MediaOutcome(MediaStatus.NONE, MediaFeedback.NO_VIDEO)
    }
}
