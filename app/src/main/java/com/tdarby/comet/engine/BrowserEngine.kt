package com.tdarby.comet.engine

import android.net.Uri
import android.net.http.SslError
import android.graphics.Bitmap
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient

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
    fun onPageFailure(failure: PageFailure) {}
    fun onRendererRecovered(url: String) {}

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

    /** Certificate error on the page. Default: cancel (safe). */
    fun onSslError(handler: SslErrorHandler, error: SslError) = handler.cancel()

    /** Server requested HTTP basic/digest auth. Default: cancel. */
    fun onHttpAuthRequest(handler: HttpAuthHandler, host: String, realm: String?) = handler.cancel()

    /** A non-http(s) link (intent:/market:/tel:/mailto:/...). Return true if handled externally. */
    fun onExternalUrl(url: String): Boolean = false

    /** The tab's renderer process died; the engine view is now unusable and must be rebuilt. */
    fun onRenderProcessGone() {}
}

/**
 * Browser surface implemented by [WebViewEngine], so the rest of the app stays decoupled from the
 * concrete WebView. (Kept as an interface to make the engine easy to swap or mock.)
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

    /** Save a `blob:` download (which DownloadManager can't fetch) by reading it in-page. */
    fun fetchBlob(url: String) {}

    /** Small in-memory preview for the TV tab switcher; never persisted. */
    fun captureThumbnail(width: Int, height: Int): Bitmap? = null

    /**
     * Resolve the link/image at a CSS-pixel point (the cursor) for a long-press context menu.
     * Returns (anchor href, image src, anchor text) via [result]; default finds nothing.
     */
    fun hitTestAt(
        xCss: Float,
        yCss: Float,
        result: (href: String?, imageSrc: String?, anchorText: String?) -> Unit
    ) = result(null, null, null)

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
