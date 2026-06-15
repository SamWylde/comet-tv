package com.tdarby.comet.engine

import android.net.Uri
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient

/** Which rendering engine backs a tab. Selectable in Settings; `full` flavor adds GECKO. */
enum class EngineType { WEBVIEW, GECKO }

/** Remote media-key actions routed into the page's <video>/<audio>. */
enum class MediaAction { PLAY_PAUSE, STOP, REWIND, FORWARD }

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

    /** Page opened an `<input type=file>`. Return true if a picker was launched. */
    fun onShowFileChooser(
        filePathCallback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean = false

    /** Page requested a runtime web permission (camera/microphone/...). Default: deny. */
    fun onPermissionRequest(request: PermissionRequest) = request.deny()

    /** Page requested the device location. Default: deny. */
    fun onGeolocationPrompt(origin: String, callback: GeolocationPermissions.Callback) =
        callback.invoke(origin, false, false)
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

    /** Current vertical scroll position of the page (0 = top). Used to detect "at top of page". */
    fun verticalScrollOffset(): Int = 0

    /** Pinch-free zoom for a 10-foot UI (driven from the menu / remote). */
    fun zoomIn() {}
    fun zoomOut() {}

    /** Route a remote media key into the page's media elements. */
    fun mediaAction(action: MediaAction) {}

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
