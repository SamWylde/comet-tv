package com.tdarby.comet.adblock

/**
 * Short, bounded record of what the blocking layers stopped on the current page of one tab.
 * Keeps hosts only (never full URLs) and is cleared when the tab starts a new page. A repeat of
 * the same host and layer is counted once and moved to the front, so one noisy tracker cannot
 * push everything else out. Thread-safe: written from the WebView request thread.
 */
class BlockedLog(private val capacity: Int = DEFAULT_CAPACITY) {

    data class Entry(val host: String, val layer: BlockLayer, val count: Int)

    private val entries = ArrayList<Entry>() // newest first

    fun record(host: String?, layer: BlockLayer) {
        val h = host?.trim()?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return
        synchronized(entries) {
            val existing = entries.indexOfFirst { it.host == h && it.layer == layer }
            val count = if (existing >= 0) entries.removeAt(existing).count + 1 else 1
            entries.add(0, Entry(h, layer, count))
            while (entries.size > capacity) entries.removeAt(entries.size - 1)
        }
    }

    /** Newest first. */
    fun entries(): List<Entry> = synchronized(entries) { ArrayList(entries) }

    fun clear() = synchronized(entries) { entries.clear() }

    companion object {
        const val DEFAULT_CAPACITY = 20
    }
}
