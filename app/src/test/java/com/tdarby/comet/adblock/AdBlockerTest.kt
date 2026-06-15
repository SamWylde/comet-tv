package com.tdarby.comet.adblock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure hostname-matching tests (no Android deps): exact host, subdomains, and no over-matching. */
class AdBlockerTest {

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
}
