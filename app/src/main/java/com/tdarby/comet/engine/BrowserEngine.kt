package com.tdarby.comet.engine

import android.view.View

/** Which rendering engine backs a tab. Selectable in Settings; `full` flavor adds GECKO. */
enum class EngineType { WEBVIEW, GECKO }

/**
 * Events an engine raises back to the UI. All methods have no-op defaults so callers only
 * override what they need.
 */
interface EngineCallbacks {
    fun onPageStarted(url: String) {}
    fun onPageFinished(url: String) {}
    fun onProgressChanged(progress: Int) {}
    fun onTitleChanged(title: String?) {}
    fun onUrlChanged(url: String) {}
    fun onNavigationStateChanged(canGoBack: Boolean, canGoForward: Boolean) {}

    /** A page requested fullscreen (HTML5 video). [onExit] returns to inline rendering. */
    fun onEnterFullscreen(fullscreenView: View, onExit: () -> Unit) {}
    fun onExitFullscreen() {}

    /** A popup/new-window was suppressed (surfaced for UI feedback). */
    fun onPopupBlocked(targetUrl: String?) {}

    /** A new-window request was rerouted to open in the current tab instead. */
    fun onOpenInCurrent(url: String) {}

    /** A popup/new-window should open as a new tab (user-initiated, or popup blocking is off). */
    fun onOpenInNewTab(url: String) {}

    /** A file download was requested by the page. */
    fun onDownloadRequested(url: String, userAgent: String?, mimeType: String?) {}
}

/**
 * Engine-agnostic browser surface. Both [WebViewEngine] and (in the `full` flavor) the GeckoView
 * engine implement this so the rest of the app never touches a concrete engine.
 */
interface BrowserEngine {
    /** The view to attach to the tab container. */
    val view: View

    fun loadUrl(url: String)
    fun currentUrl(): String?
    fun currentTitle(): String?

    fun canGoBack(): Boolean
    fun goBack()
    fun canGoForward(): Boolean
    fun goForward()
    fun reload()
    fun stop()

    /** Switch between mobile/TV and desktop user-agent + viewport. */
    fun setDesktopMode(enabled: Boolean)

    // --- Ad blocking ---
    fun setBlockingEnabled(enabled: Boolean)
    fun setSiteAllowlisted(host: String, allowlisted: Boolean)

    /** Re-apply the popup-blocking policy from current settings (engine-specific runtime flags). */
    fun applyPopupPolicy() {}

    fun onResume() {}
    fun onPause() {}
    fun destroy()
}
