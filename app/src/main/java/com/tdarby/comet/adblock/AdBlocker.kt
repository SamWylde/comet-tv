package com.tdarby.comet.adblock

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Collections

/**
 * App-wide ad/tracker blocking state and matching. Three independently toggleable layers:
 *  - **network**: blocks requests to known ad/tracker hosts (hostname blocklist; v1 engine).
 *  - **cosmetic**: injects CSS to hide leftover ad containers.
 *  - **popup**: see [PopupGuard].
 *
 * A per-site **allowlist** disables blocking for chosen hosts. Toggles/allowlist are mirrored from
 * [com.tdarby.comet.data.SettingsStore]; this object is the fast, thread-safe lookup used from the
 * WebView request thread.
 */
object AdBlocker {

    @Volatile var networkEnabled: Boolean = true
    @Volatile var cosmeticEnabled: Boolean = true
    @Volatile var popupEnabled: Boolean = true

    // Immutable snapshot, swapped atomically. Lets the WebView request thread read lock-free while
    // WorkManager merges in downloaded lists.
    @Volatile
    private var blockedHosts: Set<String> = emptySet()
    private val allowlist = Collections.synchronizedSet(HashSet<String>())

    @Volatile var loaded: Boolean = false
        private set

    /** Load the bundled hostname blocklist once. */
    @Synchronized
    fun loadFromAssets(context: Context) {
        if (loaded) return
        runCatching {
            val merged = HashSet(blockedHosts)
            context.assets.open("blocklist_hosts.txt").use { parseInto(it, merged) }
            blockedHosts = merged
        }
        loaded = true
    }

    /** Merge an additional hostname list (e.g. a downloaded filter list) into the blocklist. */
    @Synchronized
    fun mergeFromStream(stream: InputStream) {
        val merged = HashSet(blockedHosts)
        parseInto(stream, merged)
        blockedHosts = merged
    }

    fun setAllowlist(hosts: Set<String>) {
        allowlist.clear()
        allowlist.addAll(hosts)
    }

    fun currentAllowlist(): Set<String> = synchronized(allowlist) { HashSet(allowlist) }

    fun isAllowlisted(host: String?): Boolean {
        if (host == null) return false
        synchronized(allowlist) {
            return allowlist.any { host == it || host.endsWith(".$it") }
        }
    }

    /** True if a sub-resource [requestUrl] should be blocked given the current [pageHost]. */
    fun shouldBlock(requestUrl: String?, pageHost: String?): Boolean {
        if (!networkEnabled) return false
        if (isAllowlisted(pageHost)) return false
        val host = hostOf(requestUrl) ?: return false
        return isBlockedHost(host)
    }

    private fun isBlockedHost(host: String): Boolean {
        val hosts = blockedHosts // single volatile read → consistent snapshot
        var h = host
        while (true) {
            if (hosts.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
            if (!h.contains('.')) return hosts.contains(h)
        }
    }

    fun emptyResponseBody(): ByteArrayInputStream = ByteArrayInputStream(ByteArray(0))

    /** Cosmetic CSS injected on page load (hides common ad slots). Built once. */
    private val cosmeticScript: String by lazy {
        val css = COSMETIC_CSS.replace("\n", " ").replace("\"", "\\\"")
        """
            (function(){
              try {
                var s=document.createElement('style');
                s.id='__comet_cosmetic__';
                s.textContent="$css";
                (document.head||document.documentElement).appendChild(s);
              } catch(e){}
            })();
        """.trimIndent()
    }

    fun cosmeticJs(): String = cosmeticScript

    fun hostOf(url: String?): String? =
        url?.let { runCatching { Uri.parse(it).host?.lowercase() }.getOrNull() }

    private fun parseInto(stream: InputStream, into: MutableSet<String>) {
        stream.bufferedReader().forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@forEachLine
            // Accept either bare hosts or hosts-file lines like "0.0.0.0 ads.example.com".
            val token = line.split(Regex("\\s+")).lastOrNull()?.lowercase() ?: return@forEachLine
            if (token.isNotEmpty() && token != "localhost" && token.contains('.')) into.add(token)
        }
    }

    private const val COSMETIC_CSS = """
        ins.adsbygoogle, .adsbygoogle,
        [id^="google_ads_"], [id^="div-gpt-ad"], [id*="-ad-"],
        [class^="ad-"], [class*=" ad-"], .ad, .ads, .adbox, .ad-banner, .advert,
        .advertisement, .sponsored, .promoted,
        iframe[src*="doubleclick"], iframe[src*="googlesyndication"],
        iframe[src*="adservice"], iframe[src*="/ads/"] {
            display: none !important;
            visibility: hidden !important;
        }
    """
}
