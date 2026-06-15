package com.tdarby.comet.web

import android.view.ViewGroup
import android.widget.FrameLayout
import com.tdarby.comet.engine.BrowserEngine
import com.tdarby.comet.engine.EngineCallbacks

/**
 * Owns the set of open tabs. Only the active tab's engine view is attached to [container] (others
 * are detached but kept alive in memory), which keeps the cursor overlay — also a child of the
 * container — on top across tab switches.
 *
 * @param create makes an engine of the current type for a given callback (Activity supplies this).
 * @param init applies per-engine settings (desktop mode, blocking) right after creation.
 * @param ui receives events from the *active* tab only.
 * @param onChanged fired when the tab list/active tab/titles change (refresh the tab strip).
 */
class TabManager(
    private val container: FrameLayout,
    private val homeUrl: String,
    private val create: (EngineCallbacks) -> BrowserEngine,
    private val init: (BrowserEngine) -> Unit,
    private val ui: EngineCallbacks,
    private val onChanged: () -> Unit
) {
    private inner class Tab {
        lateinit var engine: BrowserEngine
        var title: String = ""
        var url: String = ""
    }

    private val tabs = mutableListOf<Tab>()
    private var activeIndex = -1

    val count: Int get() = tabs.size
    val activeEngine: BrowserEngine get() = tabs[activeIndex].engine
    val hasTabs: Boolean get() = activeIndex in tabs.indices
    fun activePosition(): Int = activeIndex
    fun titles(): List<String> = tabs.map { it.title.ifBlank { it.url.ifBlank { "New tab" } } }

    fun newTab(url: String? = homeUrl) {
        val tab = Tab()
        tab.engine = create(wrap(tab))
        init(tab.engine)
        tabs.add(tab)
        setActive(tabs.size - 1)
        url?.let { tab.engine.loadUrl(it) }
        onChanged()
    }

    fun select(index: Int) {
        if (index in tabs.indices && index != activeIndex) {
            setActive(index)
            onChanged()
        }
    }

    fun closeActive() = closeTab(activeIndex)

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val wasActive = index == activeIndex
        if (wasActive) detach(tabs[index])
        tabs[index].engine.destroy()
        tabs.removeAt(index)
        if (tabs.isEmpty()) {
            activeIndex = -1
            newTab(homeUrl)
            return
        }
        if (wasActive) {
            val target = index.coerceAtMost(tabs.size - 1)
            activeIndex = -1 // force re-attach
            setActive(target)
        } else if (index < activeIndex) {
            activeIndex-- // keep the same active tab, fix its shifted index
        }
        onChanged()
    }

    /** Rebuild every tab's engine (e.g. after the user switches engine type). */
    fun recreateAll() {
        if (tabs.isEmpty()) return
        val urls = tabs.map { it.url.ifBlank { homeUrl } }
        val previousActive = activeIndex.coerceIn(0, tabs.size - 1)
        if (activeIndex in tabs.indices) detach(tabs[activeIndex])
        tabs.forEach { it.engine.destroy() }
        tabs.clear()
        activeIndex = -1
        urls.forEach { newTab(it) }
        setActive(previousActive)
        onChanged()
    }

    fun forEachEngine(action: (BrowserEngine) -> Unit) = tabs.forEach { action(it.engine) }

    fun destroyAll() {
        if (activeIndex in tabs.indices) detach(tabs[activeIndex])
        tabs.forEach { it.engine.destroy() }
        tabs.clear()
        activeIndex = -1
    }

    fun onResume() {
        if (activeIndex in tabs.indices) tabs[activeIndex].engine.onResume()
    }

    fun onPause() {
        if (activeIndex in tabs.indices) tabs[activeIndex].engine.onPause()
    }

    private fun setActive(index: Int) {
        if (activeIndex in tabs.indices) detach(tabs[activeIndex])
        activeIndex = index
        val tab = tabs[index]
        attach(tab.engine)
        ui.onNavigationStateChanged(tab.engine.canGoBack(), tab.engine.canGoForward())
        if (tab.url.isNotBlank()) ui.onUrlChanged(tab.url)
    }

    private fun attach(engine: BrowserEngine) {
        container.addView(
            engine.view, 0,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        engine.onResume()
    }

    private fun detach(tab: Tab) {
        tab.engine.onPause()
        container.removeView(tab.engine.view)
    }

    private fun isActive(tab: Tab): Boolean = tabs.getOrNull(activeIndex) === tab

    /** Per-tab callbacks: always track this tab's title/url; forward to the UI only when active. */
    private fun wrap(tab: Tab) = object : EngineCallbacks {
        override fun onPageStarted(url: String) {
            tab.url = url
            if (isActive(tab)) ui.onPageStarted(url)
        }

        override fun onPageFinished(url: String) {
            tab.url = url
            if (isActive(tab)) ui.onPageFinished(url)
        }

        override fun onProgressChanged(progress: Int) {
            if (isActive(tab)) ui.onProgressChanged(progress)
        }

        override fun onTitleChanged(title: String?) {
            tab.title = title.orEmpty()
            if (isActive(tab)) ui.onTitleChanged(title)
            onChanged()
        }

        override fun onUrlChanged(url: String) {
            tab.url = url
            if (isActive(tab)) ui.onUrlChanged(url)
            onChanged()
        }

        override fun onNavigationStateChanged(canGoBack: Boolean, canGoForward: Boolean) {
            if (isActive(tab)) ui.onNavigationStateChanged(canGoBack, canGoForward)
        }

        override fun onEnterFullscreen(fullscreenView: android.view.View, onExit: () -> Unit) {
            if (isActive(tab)) ui.onEnterFullscreen(fullscreenView, onExit)
        }

        override fun onExitFullscreen() {
            if (isActive(tab)) ui.onExitFullscreen()
        }

        override fun onPopupBlocked(targetUrl: String?) {
            if (isActive(tab)) ui.onPopupBlocked(targetUrl)
        }

        override fun onOpenInCurrent(url: String) {
            if (isActive(tab)) ui.onOpenInCurrent(url)
        }

        override fun onOpenInNewTab(url: String) {
            if (isActive(tab)) ui.onOpenInNewTab(url)
        }

        override fun onDownloadRequested(url: String, userAgent: String?, mimeType: String?) {
            if (isActive(tab)) ui.onDownloadRequested(url, userAgent, mimeType)
        }
    }
}
