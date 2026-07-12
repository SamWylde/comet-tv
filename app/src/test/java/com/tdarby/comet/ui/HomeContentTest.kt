package com.tdarby.comet.ui

import com.tdarby.comet.data.HistoryItem
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeContentTest {

    @Test
    fun recentKeepsHistoryOrderWhileFilteringAndDeduplicating() {
        val history = listOf(
            visit("Newest", "https://example.com/", 60),
            visit("Duplicate", "https://example.com", 50),
            visit("Not a web page", "about:blank", 40),
            visit("Also not a web page", "ftp://files.example.com/file", 30),
            visit("", "http://second.example/path", 20),
            visit("Third", "https://third.example", 10)
        )

        assertEquals(
            listOf(
                HomeTile("Newest", "https://example.com/"),
                HomeTile("http://second.example/path", "http://second.example/path"),
                HomeTile("Third", "https://third.example")
            ),
            HomeContent.recent(history, limit = 8)
        )
    }

    @Test
    fun recentHonorsLimitAndZeroLimit() {
        val history = (0 until 12).map { index ->
            visit("Site $index", "https://site-$index.example/", 12L - index)
        }

        assertEquals(
            history.take(4).map { HomeTile(it.title, it.url) },
            HomeContent.recent(history, limit = 4)
        )
        assertEquals(emptyList<HomeTile>(), HomeContent.recent(history, limit = 0))
    }

    @Test
    fun recentDeduplicationIsCaseInsensitive() {
        val history = listOf(
            visit("Canonical", "https://EXAMPLE.com/Watch/", 2),
            visit("Older duplicate", "https://example.COM/watch", 1)
        )

        assertEquals(
            listOf(HomeTile("Canonical", "https://EXAMPLE.com/Watch/")),
            HomeContent.recent(history)
        )
    }

    private fun visit(title: String, url: String, time: Long) = HistoryItem(title, url, time)
}
