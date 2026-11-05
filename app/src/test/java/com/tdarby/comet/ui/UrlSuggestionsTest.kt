package com.tdarby.comet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlSuggestionsTest {

    private fun bm(title: String, url: String) = SuggestionCandidate(url, title, true)
    private fun hist(title: String, url: String) = SuggestionCandidate(url, title, false)
    private fun urls(q: String, pool: List<SuggestionCandidate>, limit: Int = 8) =
        UrlSuggestions.match(q, pool, limit).map { it.url }

    @Test
    fun matchesTitlesAsWellAsUrls() {
        val pool = listOf(
            bm("Weather Channel", "https://example.com/forecast"),
            hist("Cooking videos", "https://youtube.com/watch?v=1"),
            hist("Other", "https://other.example")
        )
        assertEquals(listOf("https://example.com/forecast"), urls("weather", pool))
        assertEquals(listOf("https://youtube.com/watch?v=1"), urls("COOKING", pool))
        assertEquals(listOf("https://other.example"), urls("other.ex", pool))
        assertEquals(listOf("https://example.com/forecast"), urls("weather forecast", pool))
        assertEquals(emptyList<String>(), urls("   ", pool))
    }

    @Test
    fun ranksBookmarksFirstThenPrefixMatchesBeforeSubstringMatches() {
        val pool = listOf(
            hist("News of the day", "https://news.example"),
            hist("Daily news", "https://daily.example"),
            bm("My news", "https://mine.example"),
            bm("News hub", "https://hub.example")
        )
        assertEquals(
            listOf("https://hub.example", "https://mine.example", "https://news.example", "https://daily.example"),
            urls("news", pool)
        )
        // URL prefix ignores the scheme and www.
        assertEquals(listOf("https://www.news.example"), urls("news.ex", listOf(hist("x", "https://www.news.example"))))
    }

    @Test
    fun listsABookmarkedUrlOnceAndHonoursTheCap() {
        val pool = listOf(hist("Site", "https://a.example")) +
            (1..10).map { bm("Site $it", "https://s$it.example") } +
            bm("Site", "https://a.example")
        assertEquals(8, urls("site", pool).size)
        assertEquals(1, UrlSuggestions.match("site", pool, 100).count { it.url == "https://a.example" })
    }
}
