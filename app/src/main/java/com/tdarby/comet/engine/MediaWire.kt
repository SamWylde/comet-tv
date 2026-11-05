package com.tdarby.comet.engine

/** A message a page frame sent to the app, after validation. */
sealed interface FrameMessage {
    /** The frame holds media and can be queried. */
    data object Hello : FrameMessage
    data class Report(val requestId: Int, val items: List<MediaItem>) : FrameMessage
    data class Reply(val requestId: Int, val ok: Boolean, val item: MediaItem?) : FrameMessage
}

/**
 * The plain-text protocol between the app and the media agent in each frame. A page can send
 * anything to the agent's object, so [parse] accepts only the exact shapes below, keeps numbers
 * inside sane bounds and never returns more than [MAX_ITEMS] items; anything else is null.
 *
 * ```
 * frame -> app   H                              the frame has media
 *                R<id>;<item>;<item>...         answer to a query
 *                A<id>;<0|1>;<item>             answer to a command (item may be empty)
 * app -> frame   Q<id>                          report your media
 *                C<id>;<index>;<op>;<number>    op is play, pause, stop, seek or rate
 * item           index,flags,time,duration,rate,area,readyState,seekable
 * flags          1 paused, 2 ended, 4 muted, 8 error, 16 audio, 32 loop
 * duration       a number, i for infinity, - for not known
 * seekable       start:end ranges joined by /
 * ```
 */
object MediaWire {
    const val MAX_CHARS = 8_192
    const val MAX_ITEMS = 48
    private const val MAX_RANGES = 3
    private const val MAX_INDEX = 4_096
    private const val MAX_SECONDS = 1.0e7
    private const val MIN_RATE = 0.0625
    private const val MAX_RATE = 16.0

    fun query(requestId: Int): String = "Q$requestId"

    fun command(requestId: Int, index: Int, op: MediaOp): String =
        "C$requestId;$index;${opName(op)};${number(op)}"

    fun opName(op: MediaOp): String = when (op) {
        MediaOp.Play -> "play"
        MediaOp.Pause -> "pause"
        MediaOp.Stop -> "stop"
        is MediaOp.SeekTo -> "seek"
        is MediaOp.SetRate -> "rate"
    }

    /** The op's number (seconds or rate) as plain decimal text; 0 when it has none. */
    fun number(op: MediaOp): String {
        val value = when (op) {
            is MediaOp.SeekTo -> op.seconds
            is MediaOp.SetRate -> op.rate
            else -> 0.0
        }
        return (if (value.isFinite()) value else 0.0).toBigDecimal().toPlainString()
    }

    fun parse(raw: String?): FrameMessage? {
        if (raw.isNullOrEmpty() || raw.length > MAX_CHARS) return null
        val parts = raw.split(';')
        val head = parts[0]
        return when (head.firstOrNull()) {
            'H' -> if (raw == "H") FrameMessage.Hello else null
            'R' -> {
                val id = requestId(head) ?: return null
                val rows = parts.drop(1)
                if (rows.size > MAX_ITEMS) return null
                val items = rows.map { parseItem(it) ?: return null }
                FrameMessage.Report(id, items)
            }
            'A' -> {
                val id = requestId(head) ?: return null
                if (parts.size != 3) return null
                val ok = when (parts[1]) {
                    "1" -> true
                    "0" -> false
                    else -> return null
                }
                val item = if (parts[2].isEmpty()) null else parseItem(parts[2]) ?: return null
                FrameMessage.Reply(id, ok, item)
            }
            else -> null
        }
    }

    private fun requestId(head: String): Int? =
        head.drop(1).takeIf { it.length in 1..9 && it.all(Char::isDigit) }?.toInt()

    private fun parseItem(text: String): MediaItem? {
        val f = text.split(',')
        if (f.size != 8) return null
        val index = f[0].toIntOrNull()?.takeIf { it in 0..MAX_INDEX } ?: return null
        val flags = f[1].toIntOrNull()?.takeIf { it in 0..63 } ?: return null
        val time = seconds(f[2]) ?: if (f[2] == "-") 0.0 else return null
        val duration = when (f[3]) {
            "i" -> Double.POSITIVE_INFINITY
            "-" -> null
            else -> seconds(f[3]) ?: return null
        }
        val rate = f[4].toDoubleOrNull()?.takeIf { it.isFinite() && it in MIN_RATE..MAX_RATE } ?: return null
        val area = f[5].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val readyState = f[6].toIntOrNull()?.takeIf { it in 0..4 } ?: return null
        val ranges = parseRanges(f[7]) ?: return null
        return MediaItem(
            index = index,
            paused = flags and 1 != 0,
            ended = flags and 2 != 0,
            muted = flags and 4 != 0,
            error = flags and 8 != 0,
            audioOnly = flags and 16 != 0,
            loop = flags and 32 != 0,
            currentTime = time,
            duration = duration,
            rate = rate,
            area = area.coerceAtMost(MAX_AREA),
            readyState = readyState,
            seekable = ranges
        )
    }

    private fun parseRanges(text: String): List<SeekRange>? {
        if (text.isEmpty()) return emptyList()
        val ranges = text.split('/')
        if (ranges.size > MAX_RANGES) return null
        return ranges.map { range ->
            val ends = range.split(':')
            if (ends.size != 2) return null
            val start = seconds(ends[0]) ?: return null
            val end = seconds(ends[1]) ?: return null
            if (end < start) return null
            SeekRange(start, end)
        }
    }

    private fun seconds(text: String): Double? =
        text.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..MAX_SECONDS }

    private const val MAX_AREA = 100_000_000L
}
