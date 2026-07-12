package com.tdarby.comet.web

import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
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
        var handled = false
        fun route(url: String?) {
            if (handled) return
            handled = true
            if (!AdBlocker.popupEnabled && !url.isNullOrBlank()) callbacks.onOpenInNewTab(url)
            else callbacks.onPopupBlocked(url)
            capture.post { capture.destroy() }
        }
        capture.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                route(request.url.toString())
                return true
            }

            override fun onPageStarted(v: WebView, url: String, favicon: android.graphics.Bitmap?) {
                route(url)
            }
        }
        transport.webView = capture
        resultMsg.sendToTarget()
        capture.postDelayed({ route(null) }, POPUP_CAPTURE_TIMEOUT_MS)
        return true
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        callbacks.onTitleChanged(title)
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean = callbacks.onShowFileChooser(filePathCallback, fileChooserParams)

    override fun onPermissionRequest(request: PermissionRequest) {
        callbacks.onPermissionRequest(request)
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback
    ) {
        callbacks.onGeolocationPrompt(origin, callback)
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

    private companion object {
        const val POPUP_CAPTURE_TIMEOUT_MS = 2_000L
    }
}
