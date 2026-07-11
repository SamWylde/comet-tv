package com.tdarby.comet.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SiteItem(val title: String, val url: String)
data class HistoryItem(val title: String, val url: String, val time: Long)

/**
 * Lightweight JSON-file persistence for bookmarks and history. Avoids Room/KSP (which lags
 * bleeding-edge Kotlin); the data is small and read once into memory at startup.
 */
class BrowserStore(context: Context) {

    private val bookmarksFile = File(context.filesDir, "bookmarks.json")
    private val historyFile = File(context.filesDir, "history.json")
    private val defaultBookmarksMarker = File(context.filesDir, "default_bookmarks_v1")
    // limitedParallelism(1) serializes writes in submission order, so rapid changes can't persist
    // out of order (a later snapshot landing before an earlier one).
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    val bookmarks: MutableList<SiteItem> = mutableListOf()
    val history: MutableList<HistoryItem> = mutableListOf()

    init {
        runCatching { bookmarks.addAll(readSites(bookmarksFile)) }
        runCatching { history.addAll(readHistory(historyFile)) }
        seedDefaultBookmarks()
    }

    /**
     * Add defaults once on both clean installs and upgrades. The marker prevents a bookmark the
     * user deliberately removes from being recreated on every launch.
     */
    private fun seedDefaultBookmarks() {
        if (defaultBookmarksMarker.exists()) return
        runCatching {
            if (bookmarks.none { it.url.trimEnd('/') == DEFAULT_BUFFSPORTS_URL.trimEnd('/') }) {
                bookmarks.add(0, SiteItem(DEFAULT_BUFFSPORTS_TITLE, DEFAULT_BUFFSPORTS_URL))
                writeSites(bookmarksFile, bookmarks)
            }
            defaultBookmarksMarker.writeText("1")
        }
    }

    fun isBookmarked(url: String): Boolean = bookmarks.any { it.url == url }

    fun addBookmark(title: String, url: String) {
        if (url.isBlank() || isBookmarked(url)) return
        bookmarks.add(0, SiteItem(title.ifBlank { url }, url))
        saveBookmarks()
    }

    fun removeBookmark(url: String) {
        if (bookmarks.removeAll { it.url == url }) saveBookmarks()
    }

    fun recordVisit(title: String, url: String) {
        if (url.isBlank() || url == "about:blank") return
        history.removeAll { it.url == url }
        history.add(0, HistoryItem(title.ifBlank { url }, url, System.currentTimeMillis()))
        if (history.size > MAX_HISTORY) history.subList(MAX_HISTORY, history.size).clear()
        saveHistory()
    }

    fun clearHistory() {
        history.clear()
        saveHistory()
    }

    private fun saveBookmarks() {
        val snapshot = bookmarks.toList()
        io.launch { writeSites(bookmarksFile, snapshot) }
    }

    private fun saveHistory() {
        val snapshot = history.toList()
        io.launch { writeHistory(historyFile, snapshot) }
    }

    private fun readSites(file: File): List<SiteItem> {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SiteItem(o.optString("title"), o.optString("url"))
        }
    }

    private fun writeSites(file: File, items: List<SiteItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("title", it.title).put("url", it.url)) }
        file.writeText(arr.toString())
    }

    private fun readHistory(file: File): List<HistoryItem> {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            HistoryItem(o.optString("title"), o.optString("url"), o.optLong("time"))
        }
    }

    private fun writeHistory(file: File, items: List<HistoryItem>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("title", it.title).put("url", it.url).put("time", it.time))
        }
        file.writeText(arr.toString())
    }

    companion object {
        private const val MAX_HISTORY = 500
        private const val DEFAULT_BUFFSPORTS_TITLE = "BuffSports"
        private const val DEFAULT_BUFFSPORTS_URL = "https://buffsports.io/"
    }
}
