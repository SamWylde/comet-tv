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

    /** The bundled baseline is intentionally tiny and safe to load during Application startup. */
    fun loadBundled() {
        AdBlocker.loadFromAssets(context)
    }

    /** Merge the much larger downloaded cache. Call from a background dispatcher. */
    fun loadCached() {
        val cache = cacheFile
        if (cache.exists()) {
            runCatching { cache.inputStream().use { AdBlocker.mergeFromStream(it) } }
        }
    }

    /** Bundled baseline + cached downloaded list (if present). */
    fun loadInitial() {
        loadBundled()
        loadCached()
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
        // HaGeZi "Light" hosts list (hosts format: "0.0.0.0 host"): ads + tracking + affiliate/
        // redirect smartlink hosts (e.g. voluum*), ~94k entries / ~2.8 MB — balanced for TV boxes.
        // Bigger HaGeZi variants (multi/pro) exist but their 360k–490k entries are too heavy here.
        const val DEFAULT_LIST_URL =
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/hosts/light.txt"
    }
}
