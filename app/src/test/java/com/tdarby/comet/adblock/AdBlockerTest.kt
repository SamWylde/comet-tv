package com.tdarby.comet.adblock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

/** Pure hostname-matching tests (no Android deps): exact host, subdomains, and no over-matching. */
class AdBlockerTest {

    @After fun restorePolicy() {
        AdBlocker.networkEnabled = true
        AdBlocker.popupEnabled = true
        AdBlocker.redirectBlockEnabled = false
        AdBlocker.setAllowlist(emptySet())
    }

    @Test
    fun matchesHostAndSubdomains() {
        AdBlocker.mergeFromStream(
            "# comment line\n0.0.0.0 doubleclick.net\nads.example.com\n".byteInputStream()
        )
        assertTrue(AdBlocker.isBlockedHost("doubleclick.net"))
        assertTrue(AdBlocker.isBlockedHost("g.doubleclick.net"))
        assertTrue(AdBlocker.isBlockedHost("ads.example.com"))
        assertTrue(AdBlocker.isBlockedHost("cdn.ads.example.com"))
    }

    @Test
    fun doesNotOverMatch() {
        AdBlocker.mergeFromStream("0.0.0.0 doubleclick.net\n".byteInputStream())
        assertFalse(AdBlocker.isBlockedHost("example.com"))
        assertFalse(AdBlocker.isBlockedHost("notdoubleclick.net"))
        assertFalse(AdBlocker.isBlockedHost("doubleclick.net.evil.com"))
    }

    @Test fun hostileTopNavigationRespectsPopupToggleAndAllowlist() {
        AdBlocker.mergeFromStream("0.0.0.0 hostile-popup.test\n".byteInputStream())
        assertTrue(AdBlocker.shouldBlockNavigation("https://hostile-popup.test/ad", "stream.test"))

        AdBlocker.popupEnabled = false
        assertFalse(AdBlocker.shouldBlockNavigation("https://hostile-popup.test/ad", "stream.test"))

        AdBlocker.popupEnabled = true
        AdBlocker.setAllowlist(setOf("stream.test"))
        assertFalse(AdBlocker.shouldBlockNavigation("https://hostile-popup.test/ad", "stream.test"))
    }

    @Test fun strictRedirectBlocksCrossSiteButKeepsSameSiteNavigation() {
        AdBlocker.redirectBlockEnabled = true
        assertTrue(AdBlocker.shouldBlockRedirect("https://affiliate.test/landing", "video.example.com"))
        assertFalse(AdBlocker.shouldBlockRedirect("https://cdn.example.com/player", "video.example.com"))

        AdBlocker.setAllowlist(setOf("example.com"))
        assertFalse(AdBlocker.shouldBlockRedirect("https://affiliate.test/landing", "video.example.com"))
    }
}
