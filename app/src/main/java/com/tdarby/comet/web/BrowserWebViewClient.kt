package com.tdarby.comet.web

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.tdarby.comet.adblock.AdBlocker
import com.tdarby.comet.adblock.PopupGuard
import com.tdarby.comet.engine.EngineCallbacks

/**
 * Reports navigation state and applies the WebView blocking layers:
 *  - network: blocks ad/tracker sub-resource requests in [shouldInterceptRequest];
 *  - popup: injects [PopupGuard] JS at page start;
 *  - cosmetic: injects hiding CSS at page finish.
 */
class BrowserWebViewClient(
    private val callbacks: EngineCallbacks
) : WebViewClient() {

    @Volatile
    private var currentHost: String? = null

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        currentHost = AdBlocker.hostOf(url)
        if (AdBlocker.popupEnabled) view.evaluateJavascript(PopupGuard.JS, null)
        callbacks.onPageStarted(url)
        callbacks.onUrlChanged(url)
        notifyNav(view)
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (AdBlocker.cosmeticEnabled && !AdBlocker.isAllowlisted(currentHost)) {
            view.evaluateJavascript(AdBlocker.cosmeticJs(), null)
        }
        callbacks.onPageFinished(url)
        callbacks.onUrlChanged(url)
        notifyNav(view)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        callbacks.onUrlChanged(url)
        notifyNav(view)
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        if (request.isForMainFrame) return null
        return if (AdBlocker.shouldBlock(request.url?.toString(), currentHost)) {
            WebResourceResponse("text/plain", "utf-8", AdBlocker.emptyResponseBody())
        } else {
            null
        }
    }

    private fun notifyNav(view: WebView) {
        callbacks.onNavigationStateChanged(view.canGoBack(), view.canGoForward())
    }
}
