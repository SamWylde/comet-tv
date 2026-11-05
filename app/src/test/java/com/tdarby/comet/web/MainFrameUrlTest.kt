package com.tdarby.comet.web

import org.junit.Assert.assertEquals
import org.junit.Test

class MainFrameUrlTest {
    @Test fun onlyTheMainFramePagesOwnTlsErrorIsFullPage() {
        val page = "https://example.test/watch?id=1"
        val cases = listOf(
            Triple(page, page, true),
            Triple("$page#t=30", page, true),
            Triple(page, "$page#top", true),
            Triple("https://cdn.example.test/poster.jpg", page, false),
            Triple("https://example.test/watch?id=2", page, false),
            Triple("https://example.test/watch", page, false),
            Triple(null, page, false),
            Triple("https://cdn.example.test/poster.jpg", null, true)
        )
        cases.forEach { (errorUrl, mainUrl, expected) ->
            assertEquals("$errorUrl vs $mainUrl", expected, isMainFrameSslError(errorUrl, mainUrl))
        }
    }
}
