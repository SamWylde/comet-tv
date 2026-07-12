package com.tdarby.comet

import com.tdarby.comet.adblock.AdBlocker
import com.tdarby.comet.data.HistoryItem
import com.tdarby.comet.ui.HomeContent
import com.tdarby.comet.web.TabManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scale regressions for startup inputs, intentionally runnable as plain JVM tests. */
class StartupScaleTest {

    @Test
    fun homeProjectionStaysBoundedAfterScanningLargeDuplicateHistory() {
        val duplicatePrefix = List(50_000) { index ->
            HistoryItem("Duplicate $index", "https://duplicate.example/", 100_000L - index)
        }
        val uniqueTail = (0 until 20).map { index ->
            HistoryItem("Unique $index", "https://unique-$index.example/", index.toLong())
        }

        val recent = HomeContent.recent(duplicatePrefix + uniqueTail, limit = 8)

        assertEquals(8, recent.size)
        assertEquals("https://duplicate.example/", recent.first().url)
        assertEquals("https://unique-6.example/", recent.last().url)
    }

    @Test
    fun largeRestoredSessionMetadataRetainsActiveEntry() {
        val restored = (0 until 20_000).map { index ->
            TabManager.TabSnapshot(
                url = "https://restored-$index.example/watch",
                title = "Restored tab $index"
            )
        }
        val activeIndex = 17_321

        assertEquals(20_000, restored.size)
        assertEquals("https://restored-17321.example/watch", restored[activeIndex].url)
        assertEquals("Restored tab 17321", restored[activeIndex].title)
    }

    @Test
    fun productionSizedCachedBlocklistCanBeMergedAndQueried() {
        val hostCount = 94_000
        val hosts = buildString(capacity = hostCount * 40) {
            repeat(hostCount) { index ->
                append("0.0.0.0 blocked-")
                append(index)
                append(".startup-scale.test\n")
            }
        }

        AdBlocker.mergeFromStream(hosts.byteInputStream())

        assertTrue(AdBlocker.isBlockedHost("blocked-0.startup-scale.test"))
        assertTrue(AdBlocker.isBlockedHost("cdn.blocked-93999.startup-scale.test"))
    }
}
