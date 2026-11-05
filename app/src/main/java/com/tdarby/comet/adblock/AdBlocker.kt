package com.tdarby.comet.adblock

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
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
    // Strict mode: cancel ANY cross-site, page-initiated top-level navigation (off by default). The
    // last resort against stream-page player redirects that rotate to *legitimate* domains (e.g.
    // shein.com used as an affiliate landing) which a hostname blocklist can't safely block.
    @Volatile var redirectBlockEnabled: Boolean = false

    // Immutable snapshot, swapped atomically. Lets the WebView request thread read lock-free while
    // WorkManager merges in downloaded lists.
    // Bundled and downloaded hosts are kept apart: a new downloaded list replaces the downloaded set
    // (so rules withdrawn upstream stop applying) while the bundled baseline always survives.
    @Volatile
    private var bundledHosts: Set<String> = emptySet()
    @Volatile
    private var downloadedHosts: Set<String> = emptySet()
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
            val bundled = HashSet<String>()
            context.assets.open("blocklist_hosts.txt").use { parseInto(it, bundled) }
            bundledHosts = bundled
            blockedHosts = bundled + downloadedHosts
        }
        loaded = true
    }

    /** Replace the downloaded host set (bundled hosts are kept); the effective set is the union. */
    @Synchronized
    fun installDownloaded(stream: InputStream) {
        installDownloaded(parseHosts(stream))
    }

    @Synchronized
    fun installDownloaded(hosts: Set<String>) {
        downloadedHosts = hosts
        blockedHosts = bundledHosts + hosts
    }

    /** Parse a hosts-format / bare-host list into a set of lowercase hosts. */
    fun parseHosts(stream: InputStream): Set<String> = HashSet<String>().also { parseInto(stream, it) }

    fun setAllowlist(hosts: Set<String>) {
        allowlist.clear()
        allowlist.addAll(hosts)
    }

    fun currentAllowlist(): Set<String> = synchronized(allowlist) { HashSet(allowlist) }

    fun isAllowlisted(host: String?): Boolean {
        if (host == null) return false
        synchronized(allowlist) {
            return allowlist.any { covers(it, host) }
        }
    }

    /** Popup blocking applies to a page on [host] when the toggle is on and the host isn't allowlisted. */
    fun popupBlockingActive(host: String?): Boolean = popupEnabled && !isAllowlisted(host)

    /** Allowlist entries (the host itself or a parent domain) that make [host] allowlisted. */
    fun coveringEntries(allowlist: Set<String>, host: String): Set<String> =
        allowlist.filterTo(HashSet()) { covers(it, host) }

    private fun covers(entry: String, host: String) = host == entry || host.endsWith(".$entry")

    /** True if a sub-resource [requestUrl] should be blocked given the current [pageHost]. */
    fun shouldBlock(requestUrl: String?, pageHost: String?): Boolean {
        if (!networkEnabled) return false
        if (isAllowlisted(pageHost)) return false
        val host = hostOf(requestUrl) ?: return false
        return isBlockedHost(host)
    }

    /**
     * True if a top-level [targetUrl] navigation should be cancelled — a forced redirect to a
     * known ad/redirect host (the popunder vector's same-tab cousin: e.g. a stream page's player
     * overlay setting `window.top.location` to an affiliate smartlink). Gated on popup blocking and
     * the per-site allowlist, and matched against the same hostname blocklist as [shouldBlock].
     */
    fun shouldBlockNavigation(targetUrl: String?, pageHost: String?): Boolean {
        if (!popupEnabled) return false
        if (isAllowlisted(pageHost)) return false
        val host = hostOf(targetUrl) ?: return false
        return isBlockedHost(host)
    }

    /**
     * Strict-mode counterpart to [shouldBlockNavigation]: with [redirectBlockEnabled] on, cancel a
     * top-level navigation to a *different registrable domain* than the current page. Address-bar
     * loads don't reach `shouldOverrideUrlLoading`, so typed URLs/searches are unaffected; only
     * page-initiated cross-site navigations (links and forced redirects alike) are caught. The
     * per-site allowlist disables it. Catches rotating redirect targets that ride legit domains.
     */
    fun shouldBlockRedirect(targetUrl: String?, pageHost: String?): Boolean {
        if (!redirectBlockEnabled) return false
        if (pageHost == null) return false
        if (isAllowlisted(pageHost)) return false
        val targetHost = hostOf(targetUrl) ?: return false
        return registrableDomain(targetHost) != registrableDomain(pageHost)
    }

    /**
     * Approximate eTLD+1 for same-site checks in strict mode: last two labels, or three when the
     * last two are a known multi-part public suffix. IP literals compare whole.
     */
    internal fun registrableDomain(rawHost: String): String {
        val host = rawHost.lowercase().trimEnd('.')
        if (host.startsWith("[") || host.contains(':') || IPV4.matches(host)) return host
        val parts = host.split('.')
        if (parts.size <= 2) return host
        val lastTwo = parts.takeLast(2).joinToString(".")
        return if (lastTwo in MULTI_PART_SUFFIXES && parts.size >= 3) {
            parts.takeLast(3).joinToString(".")
        } else lastTwo
    }

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    private val MULTI_PART_SUFFIXES = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "net.uk", "ltd.uk", "plc.uk",
        "com.au", "net.au", "org.au", "edu.au", "gov.au", "co.nz", "net.nz", "org.nz",
        "co.jp", "ne.jp", "or.jp", "ac.jp", "co.kr", "or.kr", "com.br", "net.br", "org.br",
        "com.cn", "net.cn", "org.cn", "com.tw", "com.hk", "com.sg", "com.my",
        "co.in", "net.in", "org.in", "co.za", "com.mx", "com.ar", "com.tr", "co.il",
        "com.ua", "co.id", "com.ph", "com.vn", "com.pk", "com.eg", "com.sa", "co.th",
        "github.io", "herokuapp.com", "vercel.app", "netlify.app", "pages.dev", "web.app",
        "firebaseapp.com", "appspot.com", "blogspot.com", "azurewebsites.net", "cloudfront.net"
    )

    internal fun isBlockedHost(host: String): Boolean {
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
        url?.let { runCatching { URI(it).host?.lowercase() }.getOrNull() }

    private fun parseInto(stream: InputStream, into: MutableSet<String>) {
        stream.bufferedReader().forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@forEachLine
            // Accept either bare hosts or hosts-file lines like "0.0.0.0 ads.example.com".
            val lastWhitespace = line.indexOfLast { it == ' ' || it == '\t' }
            val token = line.substring(lastWhitespace + 1).lowercase()
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
