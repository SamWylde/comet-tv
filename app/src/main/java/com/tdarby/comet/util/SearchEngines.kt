package com.tdarby.comet.util

/** Search engines offered in Settings; index aligns with R.array.search_engine_labels. */
object SearchEngines {
    val templates: List<String> = listOf(
        "https://duckduckgo.com/?q=%s",
        "https://www.google.com/search?q=%s",
        "https://www.bing.com/search?q=%s",
        "https://search.brave.com/search?q=%s"
    )

    fun indexOf(template: String): Int = templates.indexOf(template).coerceAtLeast(0)

    fun templateAt(index: Int): String = templates.getOrElse(index) { templates[0] }
}
