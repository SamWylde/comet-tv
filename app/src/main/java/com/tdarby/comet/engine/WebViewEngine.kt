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
            // Allow programmatic zoom (driven from the menu/remote) but no on-screen pinch widgets.
            setSupportZoom(true)
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

    override fun zoomIn() { webView.zoomIn() }
    override fun zoomOut() { webView.zoomOut() }

    override fun hitTestAt(
        xCss: Float,
        yCss: Float,
        result: (href: String?, imageSrc: String?, anchorText: String?) -> Unit
    ) {
        val js = "(function(){try{var el=document.elementFromPoint($xCss,$yCss);if(!el)return '{}';" +
            "var a=el.closest&&el.closest('a[href]');var i=el.closest&&el.closest('img[src]');" +
            "return JSON.stringify({href:a?a.href:null,img:i?i.src:null," +
            "text:a?(a.textContent||'').trim().slice(0,80):null});}catch(e){return '{}';}})();"
        webView.evaluateJavascript(js) { raw ->
            val o = runCatching {
                val inner = org.json.JSONTokener(raw).nextValue()
                org.json.JSONObject(if (inner is String) inner else raw)
            }.getOrNull()
            fun field(k: String) = o?.optString(k)?.takeIf { it.isNotBlank() && it != "null" }
            result(field("href"), field("img"), field("text"))
        }
    }

    override fun mediaAction(action: MediaAction) {
        val op = when (action) {
            MediaAction.PLAY_PAUSE -> "if(v.paused)v.play();else v.pause();"
            MediaAction.STOP -> "v.pause();try{v.currentTime=0;}catch(e){}"
            MediaAction.REWIND -> "try{v.currentTime=Math.max(0,v.currentTime-10);}catch(e){}"
            MediaAction.FORWARD -> "try{v.currentTime=v.currentTime+10;}catch(e){}"
        }
        webView.evaluateJavascript(
            "(function(){var m=Array.from(document.querySelectorAll('video,audio'));" +
                "var v=m.find(function(e){return !e.paused&&!e.ended;})||m[0];if(!v)return;$op})();",
            null
        )
    }

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
