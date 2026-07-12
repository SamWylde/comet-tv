package com.tdarby.comet.data

import com.tdarby.comet.web.TabManager.TabSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class TabSessionPolicyTest {
    private val many = (0 until 20_000).map { TabSnapshot("https://$it.test", "Tab $it") }

    @Test fun capsHugeSessionAndPreservesFarAwayActiveTab() {
        val session = TabSessionPolicy.bound(many, activeIndex = 17_321)
        assertEquals(100, session.tabs.size)
        assertEquals(99, session.activeIndex)
        assertEquals("https://17321.test", session.tabs[session.activeIndex].url)
    }

    @Test fun keepsExistingOrderAndActiveIndexWhenAlreadyBounded() {
        val session = TabSessionPolicy.bound(many.take(20), activeIndex = 7)
        assertEquals(many.take(20), session.tabs)
        assertEquals(7, session.activeIndex)
    }
}
