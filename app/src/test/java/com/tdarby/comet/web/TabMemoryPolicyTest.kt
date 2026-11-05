package com.tdarby.comet.web

import com.tdarby.comet.web.TabMemoryPolicy.TabState
import org.junit.Assert.assertEquals
import org.junit.Test

class TabMemoryPolicyTest {

    private fun tab(
        id: Int,
        lastActive: Long,
        active: Boolean = false,
        fullscreen: Boolean = false,
        live: Boolean = true
    ) = TabState(id, lastActive, active, fullscreen, live)

    @Test
    fun releasesTheLeastRecentlyActiveEnginesOverBudget() {
        val tabs = listOf(tab(0, 1), tab(1, 4), tab(2, 2), tab(3, 5, active = true), tab(4, 3))
        assertEquals(listOf(0, 2), TabMemoryPolicy.enginesToRelease(tabs, budget = 3))
        assertEquals(emptyList<Int>(), TabMemoryPolicy.enginesToRelease(tabs, budget = 5))
    }

    @Test
    fun activeAndFullscreenTabsStayEvenWhenOldOrOverBudget() {
        val tabs = listOf(
            tab(0, 1, fullscreen = true),
            tab(1, 2),
            tab(2, 3, active = true),
            tab(3, 4)
        )
        assertEquals(listOf(1, 3), TabMemoryPolicy.enginesToRelease(tabs, budget = 2))
        assertEquals(listOf(1, 3), TabMemoryPolicy.enginesToRelease(tabs, budget = 0))
    }

    @Test
    fun tabsWithoutAnEngineDoNotCountAgainstTheBudget() {
        val tabs = listOf(
            tab(0, 1, live = false),
            tab(1, 2, live = false),
            tab(2, 3),
            tab(3, 4, active = true)
        )
        assertEquals(emptyList<Int>(), TabMemoryPolicy.enginesToRelease(tabs, budget = 2))
    }

    @Test
    fun budgetShrinksWithDeviceMemory() {
        val gb = 1024L * 1024L * 1024L
        // lowRam, totalRam, memoryClass -> expected
        val cases = listOf(
            Triple(true, 4 * gb, 256) to 2,
            Triple(false, gb, 128) to 2,
            Triple(false, 3 * gb / 2, 192) to 2,
            Triple(false, 2 * gb, 192) to 3,
            Triple(false, 4 * gb, 64) to 2,
            Triple(false, 4 * gb, 128) to 3,
            Triple(false, 4 * gb, 256) to 4
        )
        cases.forEach { (device, expected) ->
            assertEquals(device.toString(), expected, TabMemoryPolicy.budgetFor(device.first, device.third, device.second))
        }
    }
}
