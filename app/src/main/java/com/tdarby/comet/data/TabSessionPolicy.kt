package com.tdarby.comet.data

import com.tdarby.comet.web.TabManager.TabSnapshot

/** Bounds restored chrome work while always retaining the tab that was active at shutdown. */
object TabSessionPolicy {
    const val MAX_RESTORED_TABS = 100

    data class Session(val tabs: List<TabSnapshot>, val activeIndex: Int)

    fun bound(
        tabs: List<TabSnapshot>,
        activeIndex: Int,
        limit: Int = MAX_RESTORED_TABS
    ): Session {
        require(limit > 0)
        if (tabs.isEmpty()) return Session(emptyList(), 0)
        val active = activeIndex.coerceIn(tabs.indices)
        if (tabs.size <= limit) return Session(tabs, active)

        val kept = tabs.take(limit).toMutableList()
        val boundedActive = if (active < limit) {
            active
        } else {
            kept[limit - 1] = tabs[active]
            limit - 1
        }
        return Session(kept, boundedActive)
    }
}
