package com.tdarby.comet.engine

/** A way to message one page frame. [post] returns false once the frame is gone. */
interface FrameLink {
    fun post(message: String): Boolean
}

/**
 * Keeps the frames of one page that hold media and asks them about it. Each frame runs the media
 * agent and says hello once; after that the app queries it. Frames belong to the page, so messages
 * are validated by [MediaWire], only answers to open requests are used, and unprompted chatter is
 * rationed. Everything runs on the main thread.
 *
 * @param schedule runs a task later and returns a function that cancels it.
 */
class MediaFrameHub(
    private val clock: () -> Long,
    private val schedule: (delayMs: Long, task: () -> Unit) -> (() -> Unit)
) {
    private class Frame(val key: String, val link: FrameLink) {
        var misses = 0
        var windowStart = 0L
        var unpromptedInWindow = 0
    }

    private class Query(val id: Int, val waiting: MutableSet<String>, val callback: (List<Observed>) -> Unit) {
        val found = ArrayList<Observed>()
        var cancelTimeout: () -> Unit = {}
    }

    private class Command(val id: Int, val frameKey: String, val callback: (MediaReply?) -> Unit) {
        var cancelTimeout: () -> Unit = {}
    }

    private val frames = LinkedHashMap<Any, Frame>()
    private val queries = HashMap<Int, Query>()
    private val commands = HashMap<Int, Command>()
    private var nextFrameKey = 0
    private var nextRequestId = 0

    /** The number of frames currently known to hold media. */
    val frameCount: Int get() = frames.size

    /** Handles a message from the frame identified by [sender] (the engine's reply proxy). */
    fun onMessage(sender: Any, raw: String?, linkFactory: () -> FrameLink) {
        val frame = frames[sender]
        if (frame == null) {
            // Only a hello from an unknown frame counts.
            if (raw != "H") return
            if (frames.size >= MAX_FRAMES) return
            frames[sender] = Frame("f${nextFrameKey++}", linkFactory())
            return
        }
        // A frame gets a few unprompted messages per second; answers to open requests are free.
        val expected = queries.values.any { frame.key in it.waiting } ||
            commands.values.any { it.frameKey == frame.key }
        if (!expected && !allowUnprompted(frame)) return
        when (val message = MediaWire.parse(raw) ?: return) {
            FrameMessage.Hello -> Unit
            is FrameMessage.Report -> {
                val query = queries[message.requestId] ?: return
                if (!query.waiting.remove(frame.key)) return
                frame.misses = 0
                message.items.forEach { query.found += Observed(frame.key, it) }
                if (query.waiting.isEmpty()) finishQuery(query)
            }
            is FrameMessage.Reply -> {
                val command = commands[message.requestId] ?: return
                if (command.frameKey != frame.key) return
                frame.misses = 0
                commands.remove(command.id)
                command.cancelTimeout()
                command.callback(MediaReply(message.ok, message.item))
            }
        }
    }

    /** Forget every frame because the page is changing. Open requests end empty. */
    fun reset() {
        frames.clear()
        queries.values.toList().forEach { finishQuery(it) }
        commands.values.toList().forEach { failCommand(it) }
    }

    /** Asks every known frame for its media. [callback] runs once, after all answered or time ran out. */
    fun query(callback: (List<Observed>) -> Unit) {
        val id = newRequestId()
        val waiting = HashSet<String>()
        val gone = ArrayList<Any>()
        frames.forEach { (sender, frame) ->
            if (frame.link.post(MediaWire.query(id))) waiting += frame.key else gone += sender
        }
        gone.forEach { frames.remove(it) }
        if (waiting.isEmpty()) {
            callback(emptyList())
            return
        }
        val query = Query(id, waiting, callback)
        queries[id] = query
        query.cancelTimeout = schedule(QUERY_TIMEOUT_MS) { expireQuery(query) }
    }

    /** Sends [op] to one element of one frame. [callback] gets null when the frame didn't answer. */
    fun act(frameKey: String, index: Int, op: MediaOp, callback: (MediaReply?) -> Unit) {
        val entry = frames.entries.firstOrNull { it.value.key == frameKey }
        if (entry == null) {
            callback(null)
            return
        }
        val id = newRequestId()
        if (!entry.value.link.post(MediaWire.command(id, index, op))) {
            frames.remove(entry.key)
            callback(null)
            return
        }
        val command = Command(id, frameKey, callback)
        commands[id] = command
        command.cancelTimeout = schedule(COMMAND_TIMEOUT_MS) { failCommand(command) }
    }

    private fun expireQuery(query: Query) {
        frames.entries.removeAll { (_, frame) ->
            frame.key in query.waiting && ++frame.misses >= MAX_MISSES
        }
        finishQuery(query)
    }

    private fun finishQuery(query: Query) {
        if (queries.remove(query.id) == null) return
        query.cancelTimeout()
        query.callback(query.found)
    }

    private fun failCommand(command: Command) {
        if (commands.remove(command.id) == null) return
        command.cancelTimeout()
        command.callback(null)
    }

    private fun newRequestId(): Int {
        nextRequestId = if (nextRequestId >= MAX_REQUEST_ID) 1 else nextRequestId + 1
        return nextRequestId
    }

    /** A frame may send a few messages nobody asked for per second; the rest are dropped. */
    private fun allowUnprompted(frame: Frame): Boolean {
        val now = clock()
        if (now - frame.windowStart > UNPROMPTED_WINDOW_MS) {
            frame.windowStart = now
            frame.unpromptedInWindow = 0
        }
        return ++frame.unpromptedInWindow <= UNPROMPTED_PER_WINDOW
    }

    private companion object {
        const val MAX_FRAMES = 24
        const val MAX_MISSES = 2
        const val MAX_REQUEST_ID = 999_999_999
        const val QUERY_TIMEOUT_MS = 300L
        const val COMMAND_TIMEOUT_MS = 1_500L
        const val UNPROMPTED_WINDOW_MS = 1_000L
        const val UNPROMPTED_PER_WINDOW = 5
    }
}
