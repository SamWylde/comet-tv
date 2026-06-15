package com.tdarby.comet.util

import android.net.Uri
import android.util.Patterns

/** Turns omnibox text into either a URL to load or a search query against [searchTemplate]. */
object UrlUtils {

    /** Default search template; `%s` is replaced with the URL-encoded query. */
    const val DEFAULT_SEARCH = "https://duckduckgo.com/?q=%s"

    fun toUrlOrSearch(input: String, searchTemplate: String = DEFAULT_SEARCH): String {
        val text = input.trim()
        if (text.isEmpty()) return "about:blank"

        // Already has a scheme.
        if (text.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*"))) return text

        // Looks like a domain/host (no spaces and contains a dot, or is localhost).
        val looksLikeHost = !text.contains(' ') &&
            (Patterns.WEB_URL.matcher(text).matches() || text == "localhost")
        if (looksLikeHost) return "https://$text"

        return searchTemplate.replace("%s", Uri.encode(text))
    }

    fun hostOf(url: String?): String? = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
}
