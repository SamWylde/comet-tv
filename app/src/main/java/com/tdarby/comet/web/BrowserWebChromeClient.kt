package com.tdarby.comet.web

import android.os.Message
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.tdarby.comet.adblock.AdBlocker
import com.tdarby.comet.engine.EngineCallbacks

/** Handles progress, title, and HTML5 fullscreen video for the WebView engine. */
class BrowserWebChromeClient(
    private val callbacks: EngineCallbacks
) : WebChromeClient() {

    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        callbacks.onProgressChanged(newProgress)
    }

    /**
     * Captures the would-be new window's URL (via a throwaway WebView) and routes it by policy:
     * user-initiated, or popup blocking off → open as a new tab; otherwise (programmatic popunder
     * with blocking on) → suppress and report. No real popup window is ever created.
     */
    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message
    ): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        val capture = WebView(view.context)
        capture.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (isUserGesture || !AdBlocker.popupEnabled) {
                    callbacks.onOpenInNewTab(url)
                } else {
                    callbacks.onPopupBlocked(url)
                }
                v.destroy()
                return true
            }
        }
        transport.webView = capture
        resultMsg.sendToTarget()
        return true
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        callbacks.onTitleChanged(title)
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        callbacks.onEnterFullscreen(view) { hideCustom() }
    }

    override fun onHideCustomView() {
        hideCustom()
    }

    private fun hideCustom() {
        if (customView == null) return
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
        callbacks.onExitFullscreen()
    }
}
