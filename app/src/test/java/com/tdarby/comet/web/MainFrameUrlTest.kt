package com.tdarby.comet.web

import org.junit.Assert.assertEquals
import org.junit.Test

class MainFrameUrlTest {
    private data class Case(val errorUrl: String?, val mainUrl: String?, val visibleUrl: String?, val expected: Boolean)

    @Test fun onlyTheMainFramePagesOwnTlsErrorIsFullPage() {
        val page = "https://example.test/watch?id=1"
        val typed = "https://expired.test/"
        val cases = listOf(
            Case(page, page, null, true),
            Case("$page#t=30", page, null, true),
            Case(page, "$page#top", null, true),
            Case("https://cdn.example.test/poster.jpg", page, page, false),
            Case("https://example.test/watch?id=2", page, page, false),
            Case("https://example.test/watch", page, page, false),
            Case(null, page, page, false),
            Case("https://cdn.example.test/poster.jpg", null, null, true),
            // An address-bar/bookmark load: the client still holds the previous page, while the
            // WebView's visible URL is already the URL being loaded.
            Case(typed, page, typed, true)
        )
        cases.forEach { (errorUrl, mainUrl, visibleUrl, expected) ->
            assertEquals(
                "$errorUrl vs $mainUrl / $visibleUrl",
                expected,
                isMainFrameSslError(errorUrl, mainUrl, visibleUrl)
            )
        }
    }
}
