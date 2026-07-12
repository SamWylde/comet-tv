package com.tdarby.comet.engine

import android.webkit.WebViewClient
import org.junit.Assert.assertEquals
import org.junit.Test

class PageFailureClassifierTest {
    @Test fun classifiesMainNetworkFailuresForTvErrorScreens() {
        assertEquals(PageFailureKind.DNS, classify(WebViewClient.ERROR_HOST_LOOKUP))
        assertEquals(PageFailureKind.TIMEOUT, classify(WebViewClient.ERROR_TIMEOUT))
        assertEquals(PageFailureKind.CONNECTION, classify(WebViewClient.ERROR_CONNECT))
        assertEquals(PageFailureKind.CONNECTION, classify(WebViewClient.ERROR_IO))
        assertEquals(PageFailureKind.SSL, classify(WebViewClient.ERROR_FAILED_SSL_HANDSHAKE))
        assertEquals(PageFailureKind.CONNECTION, classify(WebViewClient.ERROR_UNKNOWN))
    }

    @Test fun disconnectedNetworkOverridesChromiumErrorCode() {
        val failure = PageFailureClassifier.fromWebError(
            WebViewClient.ERROR_TIMEOUT, "https://example.test/", "timeout", isOffline = true
        )
        assertEquals(PageFailureKind.OFFLINE, failure.kind)
    }

    @Test fun preservesHttpStatusAndReason() {
        val failure = PageFailureClassifier.fromHttp(503, "https://example.test/", "Unavailable")
        assertEquals(PageFailureKind.HTTP, failure.kind)
        assertEquals(503, failure.httpStatus)
        assertEquals("Unavailable", failure.detail)
    }

    private fun classify(code: Int) =
        PageFailureClassifier.fromWebError(code, "https://example.test/", "detail").kind
}
