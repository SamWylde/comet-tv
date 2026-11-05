package com.tdarby.comet.adblock

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

    /** Install the much larger downloaded cache. Call from a background dispatcher. */
    fun loadCached() {
        val cache = cacheFile
        if (cache.exists()) {
            runCatching {
                cache.inputStream().use { validateList(it) }?.let { AdBlocker.installDownloaded(it) }
            }
        }
    }

    /** Bundled baseline + cached downloaded list (if present). */
    fun loadInitial() {
        loadBundled()
        loadCached()
    }

    /**
     * Download the remote list to a temp file, validate it, then atomically replace the cache and
     * the in-memory downloaded set. Any failure keeps the last good cache. Returns false on failure.
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(cacheFile.path + ".tmp")
        try {
            val conn = (URL(DEFAULT_LIST_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
            }
            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> LimitedInputStream(input, MAX_LIST_BYTES).copyTo(output) }
            }
            val hosts = tmp.inputStream().use { validateList(it) } ?: return@withContext false
            try {
                Files.move(
                    tmp.toPath(), cacheFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            AdBlocker.installDownloaded(hosts)
            true
        } catch (e: Exception) {
            false
        } finally {
            tmp.delete()
        }
    }

    companion object {
        private const val CACHE_NAME = "blocklist_cache.txt"
        internal const val MAX_LIST_BYTES = 20L * 1024 * 1024
        internal const val MIN_LIST_HOSTS = 1000

        /**
         * Parse a downloaded list, or null if it is not a usable filter list: over the size cap,
         * an HTML page (captive portal, error page) or too few hosts to be a real list.
         */
        internal fun validateList(
            stream: InputStream,
            maxBytes: Long = MAX_LIST_BYTES,
            minHosts: Int = MIN_LIST_HOSTS
        ): Set<String>? {
            val input = stream.buffered()
            input.mark(1024)
            val head = ByteArray(1024)
            val n = input.read(head).coerceAtLeast(0)
            input.reset()
            val start = String(head, 0, n, Charsets.ISO_8859_1).trimStart().lowercase()
            if (start.startsWith("<!doctype") || start.startsWith("<html") || start.startsWith("<")) return null
            val hosts = try {
                AdBlocker.parseHosts(LimitedInputStream(input, maxBytes))
            } catch (e: IOException) {
                return null
            }
            return hosts.takeIf { it.size >= minHosts }
        }

        private class LimitedInputStream(private val inner: InputStream, private var left: Long) : InputStream() {
            override fun read(): Int {
                val b = inner.read()
                if (b >= 0 && --left < 0) throw IOException("filter list too large")
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = inner.read(b, off, len)
                if (n > 0) {
                    left -= n
                    if (left < 0) throw IOException("filter list too large")
                }
                return n
            }
        }
        // HaGeZi "Light" hosts list (hosts format: "0.0.0.0 host"): ads + tracking + affiliate/
        // redirect smartlink hosts (e.g. voluum*), ~94k entries / ~2.8 MB — balanced for TV boxes.
        // Bigger HaGeZi variants (multi/pro) exist but their 360k–490k entries are too heavy here.
        const val DEFAULT_LIST_URL =
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/hosts/light.txt"
    }
}
