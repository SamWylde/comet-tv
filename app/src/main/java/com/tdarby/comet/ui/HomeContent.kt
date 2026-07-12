package com.tdarby.comet.ui

import com.tdarby.comet.data.HistoryItem

data class HomeTile(val title: String, val url: String)

object HomeContent {
    fun recent(history: List<HistoryItem>, limit: Int = 8): List<HomeTile> = history
        .asSequence()
        .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
        .distinctBy { it.url.trimEnd('/').lowercase() }
        .take(limit)
        .map { HomeTile(it.title.ifBlank { it.url }, it.url) }
        .toList()
}
