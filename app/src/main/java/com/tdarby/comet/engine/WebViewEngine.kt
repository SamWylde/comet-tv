package com.tdarby.comet.engine

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import com.tdarby.comet.adblock.AdBlocker
import com.tdarby.comet.web.BrowserWebChromeClient
import com.tdarby.comet.web.BrowserWebViewClient

/** System WebView (Chromium) implementation of [BrowserEngine]. */
class WebViewEngine(
    context: Context,
    private val callbacks: EngineCallbacks
) : BrowserEngine {

    private val webView = WebView(context)

    override val view: View get() = webView

    init {
        configure()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Autoplay HTML5 video without a user gesture (key for streaming pages on a remote).
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Multiple-window support is on so onCreateWindow (PopupGuard) can intercept.
            setSupportMultipleWindows(true)
            builtInZoomControls = false
            displayZoomControls = false
        }
        applyPopupPolicy()
        // Third-party cookies are kept on deliberately: embedded video players and SSO logins on
        // streaming sites frequently require them. Trackers are handled by the ad/tracker blocker.
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = BrowserWebViewClient(callbacks)
        webView.webChromeClient = BrowserWebChromeClient(callbacks)
        webView.setDownloadListener { url, userAgent, _, mimeType, _ ->
            callbacks.onDownloadRequested(url, userAgent, mimeType)
        }
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
    }

    override fun loadUrl(url: String) = webView.loadUrl(url)
    override fun currentUrl(): String? = webView.url
    override fun currentTitle(): String? = webView.title
    override fun canGoBack(): Boolean = webView.canGoBack()
    override fun goBack() = webView.goBack()
    override fun canGoForward(): Boolean = webView.canGoForward()
    override fun goForward() = webView.goForward()
    override fun reload() = webView.reload()
    override fun stop() = webView.stopLoading()
    override fun verticalScrollOffset(): Int = webView.scrollY

    override fun setDesktopMode(enabled: Boolean) {
        webView.settings.userAgentString = if (enabled) DESKTOP_UA else null
        webView.settings.useWideViewPort = enabled
        webView.settings.loadWithOverviewMode = enabled
        webView.reload()
    }

    override fun setBlockingEnabled(enabled: Boolean) {
        AdBlocker.networkEnabled = enabled
    }

    override fun applyPopupPolicy() {
        // When popup blocking is on, JS cannot auto-open windows; otherwise it can.
        webView.settings.javaScriptCanOpenWindowsAutomatically = !AdBlocker.popupEnabled
    }

    override fun setSiteAllowlisted(host: String, allowlisted: Boolean) {
        // Allowlist is managed centrally in AdBlocker via SettingsStore.
    }

    override fun onResume() {
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        webView.onPause()
        webView.pauseTimers()
    }

    override fun destroy() {
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    companion object {
        /** Desktop Chrome UA — many video players serve better streams to desktop clients. */
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
    }
}
