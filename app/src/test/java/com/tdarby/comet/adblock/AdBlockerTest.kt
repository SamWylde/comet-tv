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
        AdBlocker.cosmeticEnabled = true
        AdBlocker.popupEnabled = true
        AdBlocker.redirectBlockEnabled = false
        AdBlocker.setAllowlist(emptySet())
        AdBlocker.exceptions.clear()
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

    @Test fun pausingOneLayerForASiteLiftsOnlyThatLayerOnThatSiteAndItsSubdomains() {
        AdBlocker.installDownloaded("0.0.0.0 hostile.test\n".byteInputStream())
        AdBlocker.redirectBlockEnabled = true
        // Each layer's check, true while that layer is blocking on the given page host.
        val layers = mapOf<BlockLayer, (String) -> Boolean>(
            BlockLayer.NETWORK to { page -> AdBlocker.shouldBlock("https://hostile.test/x", page) },
            BlockLayer.COSMETIC to { page -> AdBlocker.cosmeticActive(page) },
            BlockLayer.POPUP to { page -> AdBlocker.popupBlockingActive(page) },
            BlockLayer.REDIRECT to { page -> AdBlocker.shouldBlockRedirect("https://affiliate.test/", page) }
        )
        layers.forEach { (paused, _) ->
            AdBlocker.exceptions.setPausedLayers("example.com", setOf(paused))
            layers.forEach { (layer, blocking) ->
                val onPausedSite = layer != paused
                assertEquals("$layer on example.com with $paused paused", onPausedSite, blocking("example.com"))
                assertEquals("$layer on a subdomain with $paused paused", onPausedSite, blocking("video.example.com"))
                assertTrue("$layer elsewhere with $paused paused", blocking("other.test"))
                assertTrue("$layer on a lookalike with $paused paused", blocking("notexample.com"))
            }
        }
    }

    @Test fun pausedPopupBlockingAlsoSkipsAdHostNavigationAndTheStartupScript() {
        AdBlocker.installDownloaded("0.0.0.0 hostile.test\n".byteInputStream())
        AdBlocker.exceptions.setPausedLayers("stream.test", setOf(BlockLayer.POPUP))
        assertFalse(AdBlocker.shouldBlockNavigation("https://hostile.test/ad", "stream.test"))
        assertTrue(AdBlocker.shouldBlockNavigation("https://hostile.test/ad", "other.test"))
        assertTrue("stream.test" in AdBlocker.popupScriptSkipHosts())
    }

    @Test fun turningAPauseOffAlsoLiftsAParentDomainEntryThatCoveredTheSite() {
        AdBlocker.exceptions.setPausedLayers("example.com", setOf(BlockLayer.NETWORK, BlockLayer.POPUP))
        assertTrue(AdBlocker.exceptions.isPaused(BlockLayer.NETWORK, "video.example.com"))

        AdBlocker.exceptions.setPausedLayers("Video.Example.com.", setOf(BlockLayer.POPUP))

        assertFalse(AdBlocker.exceptions.isPaused(BlockLayer.NETWORK, "video.example.com"))
        assertTrue(AdBlocker.exceptions.isPaused(BlockLayer.POPUP, "video.example.com"))
        // As with the allowlist, the parent entry is removed rather than split, so the parent and
        // its other subdomains are no longer paused either.
        assertFalse(AdBlocker.exceptions.isPaused(BlockLayer.POPUP, "example.com"))
        assertFalse(AdBlocker.exceptions.isPaused(BlockLayer.POPUP, "other.example.com"))
    }

    @Test fun downloadedReplaceDropsWithdrawnRules() {
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
