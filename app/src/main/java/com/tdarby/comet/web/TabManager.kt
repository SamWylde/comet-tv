package com.tdarby.comet.web

import android.net.Uri
import android.net.http.SslError
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import com.tdarby.comet.engine.BrowserEngine
import com.tdarby.comet.engine.EngineCallbacks
import com.tdarby.comet.engine.PageFailure
import com.tdarby.comet.data.TabSessionPolicy

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
        var engine: BrowserEngine? = null
        var title: String = ""
        var url: String = ""
    }

    private val tabs = mutableListOf<Tab>()
    private var activeIndex = -1

    val count: Int get() = tabs.size
    val activeEngine: BrowserEngine get() = requireNotNull(tabs[activeIndex].engine)
    val hasTabs: Boolean get() = activeIndex in tabs.indices
    fun activePosition(): Int = activeIndex
    fun activeUrl(): String = tabs.getOrNull(activeIndex)?.url.orEmpty()
    fun titles(): List<String> = tabs.map { it.title.ifBlank { it.url.ifBlank { "New tab" } } }

    data class TabPreview(
        val index: Int,
        val title: String,
        val url: String,
        val active: Boolean,
        val thumbnail: Bitmap?
    )

    fun previews(width: Int, height: Int): List<TabPreview> = tabs.mapIndexed { index, tab ->
        TabPreview(
            index = index,
            title = tab.title.ifBlank { tab.url.ifBlank { "New tab" } },
            url = tab.url,
            active = index == activeIndex,
            thumbnail = tab.engine?.captureThumbnail(width, height)
        )
    }

    fun newTab(url: String? = homeUrl) {
        val tab = Tab().apply { this.url = url.orEmpty() }
        tabs.add(tab)
        setActive(tabs.size - 1)
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
        tabs[index].engine?.destroy()
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
        tabs.forEach { it.engine?.destroy() }
        tabs.forEachIndexed { index, tab ->
            tab.engine = null
            tab.url = urls[index]
        }
        activeIndex = -1
        setActive(previousActive)
        onChanged()
    }

    fun forEachEngine(action: (BrowserEngine) -> Unit) = tabs.forEach { it.engine?.let(action) }

    /** Release background WebViews under memory pressure while retaining lightweight tab metadata. */
    fun releaseInactiveEngines() {
        tabs.forEachIndexed { index, tab ->
            if (index != activeIndex) {
                tab.engine?.destroy()
                tab.engine = null
            }
        }
    }

    fun destroyAll() {
        if (activeIndex in tabs.indices) detach(tabs[activeIndex])
        tabs.forEach { it.engine?.destroy() }
        tabs.clear()
        activeIndex = -1
    }

    fun onResume() {
        if (activeIndex in tabs.indices) tabs[activeIndex].engine?.onResume()
    }

    fun onPause() {
        if (activeIndex in tabs.indices) tabs[activeIndex].engine?.onPause()
    }

    private fun setActive(index: Int) {
        if (activeIndex in tabs.indices) detach(tabs[activeIndex])
        activeIndex = index
        val tab = tabs[index]
        val (engine, created) = ensureEngine(tab)
        attach(engine)
        if (created) engine.loadUrl(tab.url.ifBlank { homeUrl })
        ui.onNavigationStateChanged(engine.canGoBack(), engine.canGoForward())
        if (tab.url.isNotBlank()) ui.onUrlChanged(tab.url)
    }

    /** Restored background tabs stay as lightweight metadata until the user selects them. */
    private fun ensureEngine(tab: Tab): Pair<BrowserEngine, Boolean> {
        tab.engine?.let { return it to false }
        return create(wrap(tab)).also {
            init(it)
            tab.engine = it
        } to true
    }

    private fun attach(engine: BrowserEngine) {
        container.addView(
            engine.view, 0,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        engine.onResume()
    }

    private fun detach(tab: Tab) {
        tab.engine?.let {
            it.onPause()
            container.removeView(it.view)
        }
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

        override fun onPageFailure(failure: PageFailure) {
            if (isActive(tab)) ui.onPageFailure(failure)
        }

        override fun onEnterFullscreen(fullscreenView: android.view.View, onExit: () -> Unit) {
            if (isActive(tab)) ui.onEnterFullscreen(fullscreenView, onExit) else onExit()
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

        override fun onShowFileChooser(
            filePathCallback: ValueCallback<Array<Uri>>,
            params: WebChromeClient.FileChooserParams
        ): Boolean =
            if (isActive(tab)) ui.onShowFileChooser(filePathCallback, params) else false

        override fun onPermissionRequest(request: PermissionRequest) =
            if (isActive(tab)) ui.onPermissionRequest(request) else request.deny()

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (isActive(tab)) ui.onPermissionRequestCanceled(request)
        }

        override fun onGeolocationPrompt(origin: String, callback: GeolocationPermissions.Callback) =
            if (isActive(tab)) ui.onGeolocationPrompt(origin, callback)
            else callback.invoke(origin, false, false)

        override fun onGeolocationPromptCanceled() {
            if (isActive(tab)) ui.onGeolocationPromptCanceled()
        }

        override fun onSslError(handler: SslErrorHandler, error: SslError) =
            if (isActive(tab)) ui.onSslError(handler, error) else handler.cancel()

        override fun onHttpAuthRequest(handler: HttpAuthHandler, host: String, realm: String?) =
            if (isActive(tab)) ui.onHttpAuthRequest(handler, host, realm) else handler.cancel()

        override fun onExternalUrl(url: String): Boolean =
            if (isActive(tab)) ui.onExternalUrl(url) else false

        // Defer to the next frame: don't destroy the dead WebView from inside its own callback.
        override fun onRenderProcessGone() {
            container.post { recreate(tab) }
        }
    }

    /** Rebuild one tab's engine in place (after a renderer crash) and reload its last URL. */
    private fun recreate(tab: Tab) {
        if (tab !in tabs) return
        val wasActive = isActive(tab)
        val savedUrl = tab.url.ifBlank { homeUrl }
        if (wasActive) detach(tab)
        tab.engine?.destroy()
        tab.engine = null
        val (engine, _) = ensureEngine(tab)
        if (wasActive) attach(engine)
        if (wasActive) ui.onRendererRecovered(savedUrl) else engine.loadUrl(savedUrl)
        onChanged()
    }

    /** A persisted tab: just its URL and title (engine history/scroll is not snapshotted). */
    data class TabSnapshot(val url: String, val title: String)

    fun snapshot(): List<TabSnapshot> = tabs.map { TabSnapshot(it.url, it.title) }

    /** Restore tab metadata and instantiate only the active WebView; other tabs remain lazy. */
    fun restore(snaps: List<TabSnapshot>, active: Int) {
        if (snaps.isEmpty()) { newTab(homeUrl); return }
        val session = TabSessionPolicy.bound(snaps, active)
        session.tabs.forEach { s ->
            tabs.add(Tab().apply {
                title = s.title
                url = s.url
            })
        }
        activeIndex = -1
        setActive(session.activeIndex)
        onChanged()
    }
}
