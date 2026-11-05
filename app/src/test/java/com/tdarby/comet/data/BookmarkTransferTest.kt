package com.tdarby.comet.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BookmarkTransferTest {

    private fun site(title: String, url: String) = SiteItem(title, url)

    @Test
    fun exportThenParseKeepsEverythingIncludingAwkwardTitles() {
        val list = listOf(site("Quote \" and \\ slash\nnewline é", "https://a.example/x?y=1"), site("B", "http://b.example"))
        val parsed = BookmarkTransfer.parse(BookmarkTransfer.export(list, "2026-11-05T10:00:00Z"))
        assertEquals(BookmarkTransfer.Parsed.Ok(list, 0), parsed)
    }

    @Test
    fun rejectsFilesThatAreNotThisFormat() {
        val notOurs = listOf(
            "",
            "not json",
            "[]",
            """{"bookmarks":[{"url":"https://a.example"}]}""",
            """{"format":"other","version":1,"bookmarks":[]}""",
            """{"format":"comet-bookmarks","version":2,"bookmarks":[]}""",
            """{"format":"comet-bookmarks","version":1}""",
            """{"format":"comet-bookmarks","version":1,"bookmarks":[]} trailing"""
        )
        for (text in notOurs) {
            assertEquals(
                text,
                BookmarkTransfer.Parsed.Rejected(BookmarkTransfer.Failure.NOT_A_BOOKMARK_FILE),
                BookmarkTransfer.parse(text)
            )
        }
    }

    @Test
    fun rejectsFilesOverOneMegabyte() {
        val parsed = BookmarkTransfer.parse("{}", byteLength = BookmarkTransfer.MAX_FILE_BYTES + 1)
        assertEquals(BookmarkTransfer.Parsed.Rejected(BookmarkTransfer.Failure.TOO_LARGE), parsed)
    }

    @Test
    fun acceptsOnlyHttpAndHttpsEntries() {
        val text = """{"format":"comet-bookmarks","version":1,"bookmarks":[
            {"url":"https://ok.example","title":"Ok"},
            {"url":"javascript:alert(1)","title":"Bad"},
            {"url":"file:///sdcard/x","title":"Bad"},
            {"url":"http://","title":"No host"},
            {"url":"HTTP://Upper.example/p"},
            {"title":"No url"},
            5
        ]}"""
        val parsed = BookmarkTransfer.parse(text) as BookmarkTransfer.Parsed.Ok
        assertEquals(
            listOf(site("Ok", "https://ok.example"), site("HTTP://Upper.example/p", "HTTP://Upper.example/p")),
            parsed.items
        )
        assertEquals(5, parsed.skipped)
    }

    @Test
    fun mergeAppendsNewKeepsExistingTitlesAndDedupesByNormalizedUrl() {
        val existing = listOf(site("Mine", "https://a.example/"), site("B", "https://b.example/page"))
        val incoming = listOf(
            site("Renamed elsewhere", "HTTPS://A.EXAMPLE"),
            site("New", "https://c.example"),
            site("New again", "https://c.example/"),
            site("B different path", "https://b.example/other")
        )
        val result = BookmarkTransfer.merge(existing, incoming)
        assertEquals(
            listOf(existing[0], existing[1], site("New", "https://c.example"), site("B different path", "https://b.example/other")),
            result.merged
        )
        assertEquals(2, result.added)
        assertEquals(2, result.alreadySaved)
        assertEquals(0, result.overLimit)
    }

    @Test
    fun mergeStopsAtTheCap() {
        val existing = (1..3).map { site("E$it", "https://e$it.example") }
        val incoming = (1..4).map { site("N$it", "https://n$it.example") }
        val result = BookmarkTransfer.merge(existing, incoming, cap = 5)
        assertEquals(existing + incoming.take(2), result.merged)
        assertEquals(2, result.added)
        assertEquals(2, result.overLimit)
    }
}
