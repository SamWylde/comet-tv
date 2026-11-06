package com.tdarby.comet.engine

/** How a controller reaches the media of one page. Both calls answer on the main thread. */
interface MediaTransport {
    fun query(result: (List<Observed>) -> Unit)
    fun act(target: Observed, op: MediaOp, result: (MediaReply?) -> Unit)
}

/**
 * Runs [MediaCommand]s on the best player of a page: look at every player, choose one, work out
 * what it can do, ask the page to do it, and report the state the page confirms. One command runs
 * at a time; commands that arrive meanwhile are merged (held seeks add up) and run next.
 */
class MediaController(
    private val transport: MediaTransport,
    private val clock: () -> Long
) {
    private class Waiting(var command: MediaCommand, val callbacks: MutableList<(MediaOutcome) -> Unit>)

    private val liveWindows = LiveWindowDetector()
    private var busy = false
    private var waiting: Waiting? = null

    fun run(command: MediaCommand, callback: (MediaOutcome) -> Unit) {
        if (busy) {
            val queued = waiting
            if (queued == null) {
                waiting = Waiting(command, mutableListOf(callback))
            } else {
                queued.command = merge(queued.command, command)
                queued.callbacks += callback
            }
            return
        }
        busy = true
        transport.query { observed -> onObserved(command, observed, callback) }
    }

    private fun onObserved(command: MediaCommand, observed: List<Observed>, callback: (MediaOutcome) -> Unit) {
        val best = MediaPlayerChooser.choose(observed)
        if (best == null) {
            finish(MediaOutcome.NO_VIDEO, callback)
            return
        }
        val item = withWindow(best)
        when (val plan = MediaPlanner.plan(command, item)) {
            MediaPlan.Nothing -> finish(MediaOutcome(MediaPlanner.statusOf(item)), callback)
            is MediaPlan.Reject -> finish(MediaOutcome(MediaPlanner.statusOf(item), plan.feedback), callback)
            is MediaPlan.Send -> transport.act(best, plan.op) { reply ->
                val after = reply?.item?.copy(movingWindow = item.movingWindow) ?: item
                val feedback = when {
                    reply == null -> MediaFeedback.FAILED
                    reply.ok -> MediaFeedback.NONE
                    plan.op == MediaOp.Play -> MediaFeedback.PLAY_BLOCKED
                    plan.op is MediaOp.SetRate -> MediaFeedback.CANNOT_CHANGE_SPEED
                    plan.op is MediaOp.SeekTo -> MediaFeedback.CANNOT_SEEK
                    else -> MediaFeedback.FAILED
                }
                finish(MediaOutcome(MediaPlanner.statusOf(after), feedback), callback)
            }
        }
    }

    private fun withWindow(observed: Observed): MediaItem {
        val moving = liveWindows.observe("${observed.frameKey}:${observed.item.index}", clock(), observed.item)
        return observed.item.copy(movingWindow = moving)
    }

    private fun finish(outcome: MediaOutcome, callback: (MediaOutcome) -> Unit) {
        busy = false
        callback(outcome)
        val next = waiting ?: return
        waiting = null
        run(next.command) { result -> next.callbacks.forEach { it(result) } }
    }

    private fun merge(queued: MediaCommand, incoming: MediaCommand): MediaCommand = when {
        queued is MediaCommand.Seek && incoming is MediaCommand.Seek ->
            MediaCommand.Seek(queued.deltaSeconds + incoming.deltaSeconds)
        // The controls refresh every second; that must never replace a press still waiting to run.
        // The queued command reports the state anyway.
        incoming == MediaCommand.Refresh -> queued
        else -> incoming
    }
}

/**
 * Tries [primary] first and falls back to [secondary] when it finds no media, so a page whose
 * frames never said hello still gets the same-origin search. Elements found by [secondary] carry
 * frame keys starting with [SECONDARY_PREFIX] and are acted on through it.
 */
class FallbackTransport(
    private val primary: MediaTransport,
    private val secondary: MediaTransport
) : MediaTransport {
    override fun query(result: (List<Observed>) -> Unit) {
        primary.query { found ->
            if (found.isNotEmpty()) result(found) else secondary.query(result)
        }
    }

    override fun act(target: Observed, op: MediaOp, result: (MediaReply?) -> Unit) {
        val transport = if (target.frameKey.startsWith(SECONDARY_PREFIX)) secondary else primary
        transport.act(target, op, result)
    }

    companion object {
        const val SECONDARY_PREFIX = "s"
    }
}

/** [MediaTransport] over the media agent in each frame, through [MediaFrameHub]. */
class HubTransport(private val hub: MediaFrameHub) : MediaTransport {
    override fun query(result: (List<Observed>) -> Unit) = hub.query(result)

    override fun act(target: Observed, op: MediaOp, result: (MediaReply?) -> Unit) =
        hub.act(target.frameKey, target.item.index, op, result)
}

/**
 * [MediaTransport] that runs a script in the page and reads its answer. It reaches the top page and
 * same-origin frames only, which is why it is the fallback. [evaluate] gives the script's result as
 * JavaScript sends it back from `evaluateJavascript`: a JSON string literal, or null.
 */
class ScriptTransport(
    private val evaluate: (script: String, callback: (String?) -> Unit) -> Unit
) : MediaTransport {
    override fun query(result: (List<Observed>) -> Unit) {
        evaluate(MediaScripts.queryScript()) { raw ->
            val report = MediaWire.parse(unquote(raw)) as? FrameMessage.Report
            result(report?.items.orEmpty().map { Observed(KEY, it) })
        }
    }

    override fun act(target: Observed, op: MediaOp, result: (MediaReply?) -> Unit) {
        evaluate(MediaScripts.commandScript(target.item.index, op)) { raw ->
            val reply = MediaWire.parse(unquote(raw)) as? FrameMessage.Reply
            result(reply?.let { MediaReply(it.ok, it.item) })
        }
    }

    private companion object {
        const val KEY = FallbackTransport.SECONDARY_PREFIX

        /** `evaluateJavascript` returns a string as a quoted literal; ours never contain escapes. */
        fun unquote(raw: String?): String? =
            raw?.takeIf { it.length >= 2 && it.first() == '"' && it.last() == '"' }
                ?.substring(1, raw.length - 1)
                ?.takeIf { '\\' !in it && '"' !in it }
    }
}
