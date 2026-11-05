package com.tdarby.comet.adblock

/** The independent blocking layers a site exception can pause (see [AdBlocker]). */
enum class BlockLayer { NETWORK, COSMETIC, POPUP, REDIRECT }

/**
 * Temporary, per-host pauses of single blocking layers. Held in memory only: they are never
 * persisted, so they end when Comet's process does. A host entry also covers its subdomains, the
 * same rule the allowlist follows. Thread-safe: read from the WebView request thread.
 */
class SiteExceptions {
    private val paused = HashMap<BlockLayer, Set<String>>()

    private fun key(host: String) = host.trim().lowercase().trimEnd('.')

    /** True if [layer] is paused for a page on [host] (the host itself or a parent entry). */
    fun isPaused(layer: BlockLayer, host: String?): Boolean {
        if (host == null) return false
        val h = key(host)
        return synchronized(paused) { paused[layer]?.any { AdBlocker.covers(it, h) } == true }
    }

    /** Layers paused for [host]. */
    fun pausedLayers(host: String?): Set<BlockLayer> =
        BlockLayer.entries.filterTo(HashSet()) { isPaused(it, host) }

    /** Hosts for which [layer] is paused; scripts injected at document start embed these. */
    fun hostsFor(layer: BlockLayer): Set<String> =
        synchronized(paused) { paused[layer]?.let { HashSet(it) } ?: emptySet() }

    /**
     * Make [layers] exactly the paused set for [host]: pauses the missing ones on [host] and lifts
     * the rest, including entries on a parent domain that covered it.
     */
    fun setPausedLayers(host: String, layers: Set<BlockLayer>) {
        val h = key(host)
        if (h.isEmpty()) return
        synchronized(paused) {
            BlockLayer.entries.forEach { layer ->
                val entries = (paused[layer] ?: emptySet()).filterNot { AdBlocker.covers(it, h) }.toMutableSet()
                if (layer in layers) entries.add(h)
                if (entries.isEmpty()) paused.remove(layer) else paused[layer] = entries
            }
        }
    }

    fun clear() = synchronized(paused) { paused.clear() }
}
