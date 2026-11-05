package com.tdarby.comet.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedLogTest {
    @Test fun keepsOnlyTheNewestDistinctItemsCountsRepeatsAndClears() {
        val log = BlockedLog(capacity = 3)
        log.record("a.test", BlockLayer.NETWORK)
        log.record("b.test", BlockLayer.NETWORK)
        log.record("a.test", BlockLayer.NETWORK) // repeat: counted, moved to the front
        log.record("a.test", BlockLayer.POPUP) // same host, other layer: its own item
        log.record("c.test", BlockLayer.REDIRECT) // fourth distinct item pushes the oldest out

        assertEquals(
            listOf(
                BlockedLog.Entry("c.test", BlockLayer.REDIRECT, 1),
                BlockedLog.Entry("a.test", BlockLayer.POPUP, 1),
                BlockedLog.Entry("a.test", BlockLayer.NETWORK, 2)
            ),
            log.entries()
        )

        log.clear()

        assertTrue(log.entries().isEmpty())
    }

    @Test fun keepsHostsOnlyAndIgnoresMissingHosts() {
        val log = BlockedLog()
        log.record("Ads.Example.COM.", BlockLayer.NETWORK)
        log.record(null, BlockLayer.NETWORK)
        log.record("  ", BlockLayer.POPUP)

        assertEquals(listOf(BlockedLog.Entry("ads.example.com", BlockLayer.NETWORK, 1)), log.entries())
    }
}
