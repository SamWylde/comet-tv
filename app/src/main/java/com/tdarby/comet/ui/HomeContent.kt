package com.tdarby.comet.ui

import com.tdarby.comet.data.HistoryItem
import com.tdarby.comet.data.PlaybackRecord
import com.tdarby.comet.data.PlaybackResumePolicy

data class HomeTile(val title: String, val url: String, val subtitle: String? = null)

object HomeContent {
    fun recent(history: List<HistoryItem>, limit: Int = 8): List<HomeTile> = history
        .asSequence()
        .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
        .distinctBy { it.url.trimEnd('/').lowercase() }
        .take(limit)
        .map { HomeTile(it.title.ifBlank { it.url }, it.url) }
        .toList()

    /** The newest [limit] unfinished videos as tiles; [timeLeft] words the minutes left under the title. */
    fun continueWatching(
        records: List<PlaybackRecord>,
        limit: Int = 4,
        timeLeft: (minutes: Int) -> String
    ): List<HomeTile> = records
        .asSequence()
        .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
        .take(limit)
        .map { HomeTile(it.title.ifBlank { it.url }, it.url, timeLeft(PlaybackResumePolicy.minutesLeft(it))) }
        .toList()

    /** How many tiles of [cellWidth] (tile plus margins) fit in [availableWidth], between 1 and [maxColumns]. */
    fun columns(availableWidth: Int, cellWidth: Int, maxColumns: Int = 4): Int =
        if (availableWidth <= 0 || cellWidth <= 0) 1 else (availableWidth / cellWidth).coerceIn(1, maxColumns)
}
