package com.tdarby.comet.web

import android.app.ActivityManager
import android.content.Context

/**
 * Decides how many tabs may keep a live WebView and which ones give theirs up. The active tab and
 * the fullscreen owner always stay live; everything else competes for the remaining slots by recency.
 */
object TabMemoryPolicy {

    data class TabState(
        val id: Int,
        /** Larger = activated more recently. */
        val lastActive: Long,
        val isActive: Boolean,
        val isFullscreenOwner: Boolean,
        val isLive: Boolean
    )

    /**
     * Ids whose engines should be released so that at most [budget] stay live, oldest first. A budget
     * smaller than the number of protected tabs still keeps those and releases every other live tab.
     */
    fun enginesToRelease(tabs: List<TabState>, budget: Int): List<Int> {
        val live = tabs.filter { it.isLive }
        val protectedCount = live.count { it.isActive || it.isFullscreenOwner }
        val roomForOthers = (budget - protectedCount).coerceAtLeast(0)
        return live.filter { !it.isActive && !it.isFullscreenOwner }
            .sortedByDescending { it.lastActive }
            .drop(roomForOthers)
            .reversed()
            .map { it.id }
    }

    private const val MB = 1024L * 1024L

    /** Live engines allowed for a device: 2 on low-RAM or about 1.5 GB or less, 3 on about 2 GB, else 4. */
    fun budgetFor(isLowRamDevice: Boolean, memoryClassMb: Int, totalRamBytes: Long): Int {
        val totalMb = totalRamBytes / MB
        return when {
            isLowRamDevice || totalMb <= 1536 || memoryClassMb < 96 -> 2
            totalMb <= 2560 || memoryClassMb < 160 -> 3
            else -> 4
        }
    }

    fun budgetFor(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 2
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return budgetFor(am.isLowRamDevice, am.memoryClass, info.totalMem)
    }
}
