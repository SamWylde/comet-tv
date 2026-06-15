package com.tdarby.comet.adblock

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads the bundled blocklist at startup and refreshes it from a remote hosts-format filter list.
 * Downloaded lists are cached and merged on top of the bundled baseline; download failures keep the
 * last-good cache (graceful degradation).
 */
class FilterListRepository(private val context: Context) {

    private val cacheFile: File get() = File(context.filesDir, CACHE_NAME)

    /** Bundled baseline + cached downloaded list (if present). Safe to call once at startup. */
    fun loadInitial() {
        AdBlocker.loadFromAssets(context)
        val cache = cacheFile
        if (cache.exists()) {
            runCatching { cache.inputStream().use { AdBlocker.mergeFromStream(it) } }
        }
    }

    /** Download the remote list, cache it, and merge it in. Returns false on any failure. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(DEFAULT_LIST_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
            }
            conn.inputStream.use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
            cacheFile.inputStream().use { AdBlocker.mergeFromStream(it) }
            true
        }.getOrDefault(false)
    }

    companion object {
        private const val CACHE_NAME = "blocklist_cache.txt"
        // AdAway's ad/tracker hosts list (hosts format: "0.0.0.0 host"); moderate size for TV boxes.
        const val DEFAULT_LIST_URL =
            "https://raw.githubusercontent.com/AdAway/adaway.github.io/master/hosts.txt"
    }
}
