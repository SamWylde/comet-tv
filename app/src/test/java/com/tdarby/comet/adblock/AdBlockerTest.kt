package com.tdarby.comet.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        AdBlocker.installDownloaded(
            "# comment line\n0.0.0.0 doubleclick.net\nads.example.com\n".byteInputStream()
        )
        assertTrue(AdBlocker.isBlockedHost("doubleclick.net"))
        assertTrue(AdBlocker.isBlockedHost("g.doubleclick.net"))
        assertTrue(AdBlocker.isBlockedHost("ads.example.com"))
        assertTrue(AdBlocker.isBlockedHost("cdn.ads.example.com"))
    }

    @Test
    fun doesNotOverMatch() {
        AdBlocker.installDownloaded("0.0.0.0 doubleclick.net\n".byteInputStream())
        assertFalse(AdBlocker.isBlockedHost("example.com"))
        assertFalse(AdBlocker.isBlockedHost("notdoubleclick.net"))
        assertFalse(AdBlocker.isBlockedHost("doubleclick.net.evil.com"))
    }

    @Test fun hostileTopNavigationRespectsPopupToggleAndAllowlist() {
        AdBlocker.installDownloaded("0.0.0.0 hostile-popup.test\n".byteInputStream())
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

    @Test fun strictRedirectTellsSitesApartOnMultiPartSuffixesAndIps() {
        AdBlocker.redirectBlockEnabled = true
        // (target, page, expected blocked)
        listOf(
            Triple("https://a.co.uk/x", "b.co.uk", true),
            Triple("https://x.example.co.uk/x", "example.co.uk", false),
            Triple("https://foo.github.io/x", "bar.github.io", true),
            Triple("https://1.2.3.4/x", "5.6.3.4", true),
            Triple("https://1.2.3.4/x", "1.2.3.4", false),
            Triple("https://CDN.Example.COM./x", "video.example.com", false),
        ).forEach { (target, page, blocked) ->
            assertEquals("$target from $page", blocked, AdBlocker.shouldBlockRedirect(target, page))
        }
    }

    @Test fun coveringEntriesIncludeParentDomains() {
        val list = setOf("example.com", "video.example.com", "other.test")
        assertEquals(setOf("example.com", "video.example.com"), AdBlocker.coveringEntries(list, "video.example.com"))
        assertEquals(setOf("example.com"), AdBlocker.coveringEntries(list, "a.example.com"))
        assertEquals(emptySet<String>(), AdBlocker.coveringEntries(list, "notexample.com"))
    }

    @Test fun popupBlockingIsOffOnAllowlistedHosts() {
        AdBlocker.setAllowlist(setOf("example.com"))
        assertFalse(AdBlocker.popupBlockingActive("video.example.com"))
        assertTrue(AdBlocker.popupBlockingActive("other.test"))
    }

    @Test fun downloadedReplaceDropsWithdrawnRulesButKeepsBundled() {
        AdBlocker.installDownloaded("v1-only.test\nboth.test\n".byteInputStream())
        assertTrue(AdBlocker.isBlockedHost("v1-only.test"))
        AdBlocker.installDownloaded("both.test\nv2-only.test\n".byteInputStream())
        assertFalse(AdBlocker.isBlockedHost("v1-only.test"))
        assertTrue(AdBlocker.isBlockedHost("both.test"))
        assertTrue(AdBlocker.isBlockedHost("v2-only.test"))
    }

    @Test fun listValidationRejectsHtmlTooSmallAndOversizedLists() {
        val big = (1..1500).joinToString("\n") { "0.0.0.0 h$it.test" }
        assertEquals(1500, FilterListRepository.validateList(big.byteInputStream())?.size)
        assertNull(FilterListRepository.validateList("<!DOCTYPE html><html>$big</html>".byteInputStream()))
        assertNull(FilterListRepository.validateList("0.0.0.0 a.test\n0.0.0.0 b.test\n".byteInputStream()))
        assertNull(FilterListRepository.validateList(big.byteInputStream(), maxBytes = 100))
    }
}
