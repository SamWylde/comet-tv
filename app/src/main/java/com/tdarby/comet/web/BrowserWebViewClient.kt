package com.tdarby.comet.web

import android.graphics.Bitmap
import android.net.http.SslError
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Context
import android.util.Log
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.tdarby.comet.adblock.AdBlocker
import com.tdarby.comet.adblock.PopupGuard
import com.tdarby.comet.engine.EngineCallbacks
import com.tdarby.comet.engine.PageFailureClassifier

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

    /**
     * Intercepts top-level navigations. A stream page's player overlay redirects the *current* tab
     * (`window.top.location = <ad>`) rather than opening a popup, so [PopupGuard]/`onCreateWindow`
     * never see it; this is the only hook that does. Cancels main-frame navigations to blocklisted
     * ad/redirect hosts (popup blocking on, site not allowlisted) and leaves the user on the page.
     */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        val url = request.url?.toString()
        // Non-web links (intent:, market:, tel:, mailto:, ...) can't load in a WebView — hand them
        // to the right app (with confirmation) instead of erroring.
        val scheme = request.url?.scheme?.lowercase()
        if (scheme != null && scheme != "http" && scheme != "https" && scheme != "about" &&
            scheme != "data" && scheme != "javascript" && url != null
        ) {
            callbacks.onExternalUrl(url)
            return true
        }
        if (AdBlocker.shouldBlockNavigation(url, currentHost)) {
            Log.i(TAG, "Blocked ad-host redirect: $url (from $currentHost)")
            callbacks.onPopupBlocked(url)
            return true
        }
        if (AdBlocker.shouldBlockRedirect(url, currentHost)) {
            Log.i(TAG, "Blocked cross-site redirect (strict): $url (from $currentHost)")
            callbacks.onPopupBlocked(url)
            return true
        }
        return false
    }

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

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        callbacks.onSslError(handler, error)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        if (request.isForMainFrame) {
            callbacks.onPageFailure(
                PageFailureClassifier.fromWebError(
                    error.errorCode,
                    request.url.toString(),
                    error.description?.toString().orEmpty(),
                    isOffline(view.context)
                )
            )
        }
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        if (request.isForMainFrame && errorResponse.statusCode >= 400) {
            callbacks.onPageFailure(
                PageFailureClassifier.fromHttp(
                    errorResponse.statusCode,
                    request.url.toString(),
                    errorResponse.reasonPhrase.orEmpty()
                )
            )
        }
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler,
        host: String,
        realm: String?
    ) {
        callbacks.onHttpAuthRequest(handler, host, realm)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        // Returning true tells the framework we handled it, so the app isn't killed. The dead
        // WebView is unusable; ask the UI to rebuild this tab's engine.
        Log.w(TAG, "Renderer gone (didCrash=${detail.didCrash()}); rebuilding tab")
        callbacks.onRenderProcessGone()
        return true
    }

    private fun notifyNav(view: WebView) {
        callbacks.onNavigationStateChanged(view.canGoBack(), view.canGoForward())
    }

    private fun isOffline(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return true
        val capabilities = manager.getNetworkCapabilities(network) ?: return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        const val TAG = "CometNav"
    }
}
