package com.tdarby.comet.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Where the user stopped a long video on one page. Times are in seconds. */
data class PlaybackRecord(
    val url: String,
    val title: String,
    val position: Double,
    val duration: Double,
    val updatedAt: Long
)

/** What to do with a playback sample: store it, forget the page's record, or leave things alone. */
enum class ResumeAction { SAVE, DROP, IGNORE }

/** The rules for continue-watching records, kept free of Android so they can be tested on the JVM. */
object PlaybackResumePolicy {
    const val MIN_DURATION_SECONDS = 300.0
    const val EDGE_SECONDS = 30.0
    const val MAX_RECORDS = 30
    const val SAVE_INTERVAL_MS = 15_000L
    const val MATCH_TOLERANCE_SECONDS = 2.0

    /** A video is worth remembering when it is finite and at least five minutes long. */
    fun isTrackable(duration: Double, live: Boolean): Boolean =
        !live && duration.isFinite() && duration >= MIN_DURATION_SECONDS

    /** Finished (within 30 s of the end) or barely started (under 30 s in) means no record. */
    fun action(position: Double, duration: Double, live: Boolean): ResumeAction = when {
        !isTrackable(duration, live) || !position.isFinite() -> ResumeAction.IGNORE
        position < EDGE_SECONDS || position > duration - EDGE_SECONDS -> ResumeAction.DROP
        else -> ResumeAction.SAVE
    }

    /** True when a periodic save is due; a forced save (pause, tab switch) ignores the interval. */
    fun saveDue(lastSavedAt: Long, now: Long, force: Boolean): Boolean =
        force || lastSavedAt == 0L || now - lastSavedAt >= SAVE_INTERVAL_MS

    /** The loaded video is the recorded one when the durations agree within 2 s. */
    fun matches(record: PlaybackRecord, loadedDuration: Double): Boolean =
        loadedDuration.isFinite() && abs(loadedDuration - record.duration) <= MATCH_TOLERANCE_SECONDS

    /** Offer only when the video is the recorded one and playback hasn't already got past the spot. */
    fun shouldOffer(record: PlaybackRecord, loadedDuration: Double, currentPosition: Double): Boolean =
        matches(record, loadedDuration) && currentPosition < record.position - EDGE_SECONDS / 3

    /** Newest first, one record per page, at most [max]. */
    fun upsert(records: List<PlaybackRecord>, record: PlaybackRecord, max: Int = MAX_RECORDS): List<PlaybackRecord> =
        (listOf(record) + records.filter { it.url != record.url })
            .sortedByDescending { it.updatedAt }
            .take(max)

    /** Page identity for records: the address without a #fragment. */
    fun key(url: String): String = url.substringBefore('#')

    /** Whole minutes left, rounded up, at least 1. */
    fun minutesLeft(record: PlaybackRecord): Int =
        Math.ceil((record.duration - record.position).coerceAtLeast(0.0) / 60.0).toInt().coerceAtLeast(1)

    /** "1:05:09" or "12:34". */
    fun clock(seconds: Double): String {
        val total = seconds.coerceAtLeast(0.0).toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

/**
 * Continue-watching records in a small JSON file. Reads are served from memory; writes happen
 * on a serialized background thread. Never created for private tabs.
 */
class PlaybackResumeStore(context: Context) {

    private val file = File(context.filesDir, "playback.json")

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val lock = Any()
    @Volatile private var records: List<PlaybackRecord> = emptyList()

    /** Newest first. Empty until [load] has finished. */
    fun all(): List<PlaybackRecord> = records

    fun find(url: String): PlaybackRecord? = records.firstOrNull { it.url == PlaybackResumePolicy.key(url) }

    /** Read the file into memory. Blocking: call from a background thread. */
    fun load() {
        val loaded = readRecords(file)
        synchronized(lock) { records = (records + loaded).sortedByDescending { it.updatedAt }
                .distinctBy { it.url }.take(PlaybackResumePolicy.MAX_RECORDS) }
    }

    fun save(record: PlaybackRecord) = update { PlaybackResumePolicy.upsert(it, record) }

    fun drop(url: String) = update { list -> list.filter { it.url != PlaybackResumePolicy.key(url) } }

    fun clear() = update { emptyList() }

    private fun update(change: (List<PlaybackRecord>) -> List<PlaybackRecord>) {
        val json = synchronized(lock) {
            val next = change(records)
            if (next == records) return
            records = next
            encode(next)
        }
        io.launch { runCatching { AtomicFiles.writeText(file, json) } }
    }

    companion object {
        internal fun readRecords(file: File): List<PlaybackRecord> =
            AtomicFiles.readOrSetAside(file, emptyList()) { text -> decode(text) }

        internal fun encode(list: List<PlaybackRecord>): String {
            val arr = JSONArray()
            list.forEach {
                arr.put(
                    JSONObject().put("u", it.url).put("t", it.title)
                        .put("p", it.position).put("d", it.duration).put("a", it.updatedAt)
                )
            }
            return JSONObject().put("records", arr).toString()
        }

        internal fun decode(text: String): List<PlaybackRecord> {
            val arr = JSONObject(text).optJSONArray("records") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val url = o.optString("u")
                if (url.isBlank()) null
                else PlaybackRecord(url, o.optString("t"), o.optDouble("p"), o.optDouble("d"), o.optLong("a"))
            }.take(PlaybackResumePolicy.MAX_RECORDS)
        }
    }
}
